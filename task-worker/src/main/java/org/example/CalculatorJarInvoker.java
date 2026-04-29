package org.example;

import org.example.proto.UniversalTask;

import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.Field;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

public final class CalculatorJarInvoker implements AutoCloseable {

    private static final String TARGET_CLASS_ANNOTATION = "TargetClass";
    private static final String TARGET_METHOD_ANNOTATION = "TargetMethod";
    private static final String ROLE_LIBRARY_INFO = "libraryInfo";

    private final Path jarPath;
    private final URLClassLoader classLoader;

    private volatile Resolved resolved;

    public CalculatorJarInvoker(Path jarPath) throws Exception {
        if (!Files.isRegularFile(jarPath)) {
            throw new IllegalArgumentException("Файл JAR не найден: " + jarPath.toAbsolutePath());
        }
        this.jarPath = jarPath;
        this.classLoader = new URLClassLoader(
                new URL[]{jarPath.toUri().toURL()},
                ClassLoader.getSystemClassLoader().getParent());
    }

    public String getLibraryInfo() {
        try {
            Resolved r = resolve();
            if (r.libraryInfoMethod == null) {
                return "в JAR нет @TargetMethod(\"" + ROLE_LIBRARY_INFO + "\")";
            }
            Object targetInstance = instantiateIfNeeded(r.targetClass, r.libraryInfoMethod);
            return String.valueOf(r.libraryInfoMethod.invoke(targetInstance));
        } catch (Exception e) {
            return "ошибка getLibraryInfo: " + e.getMessage();
        }
    }

    public Object invokeBatch(UniversalTask task) throws Exception {
        Resolved r = resolve();
        Method target = selectTargetMethod(r, task);
        Class<?> calcClass = r.targetClass;

        Object[] args = buildArgsFromParamAnnotations(target, task, calcClass);
        Object targetInstance = instantiateIfNeeded(calcClass, target);
        return target.invoke(targetInstance, args);
    }

    private static Class<?> resolvePassengerClass(Class<?> calcClass, Method batchMethod) throws ClassNotFoundException {
        for (int i = 0; i < batchMethod.getParameterCount(); i++) {
            Class<?> elem = listElementClass(batchMethod, i);
            if (elem != null && elem.getSimpleName().equals("Passenger")) {
                return elem;
            }
        }
        for (Class<?> inner : calcClass.getDeclaredClasses()) {
            if (inner.getSimpleName().equals("Passenger")) {
                return inner;
            }
        }
        throw new ClassNotFoundException("Не удалось определить класс Passenger для метода " + batchMethod.getName());
    }

    private static Class<?> listElementClass(Method method, int paramIndex) {
        Type[] g = method.getGenericParameterTypes();
        if (paramIndex >= g.length) {
            return null;
        }
        Type t = g[paramIndex];
        if (!(t instanceof ParameterizedType)) {
            return null;
        }
        ParameterizedType pt = (ParameterizedType) t;
        if (!(pt.getRawType() instanceof Class) || !List.class.isAssignableFrom((Class<?>) pt.getRawType())) {
            return null;
        }
        Type[] args = pt.getActualTypeArguments();
        if (args.length != 1 || !(args[0] instanceof Class)) {
            return null;
        }
        return (Class<?>) args[0];
    }

    private static Object[] buildArgsFromParamAnnotations(Method method, UniversalTask task, Class<?> calcClass)
            throws ReflectiveOperationException, ClassNotFoundException, IOException {

        Parameter[] params = method.getParameters();
        Object[] out = new Object[params.length];
        ParsedPayload payload = parsePayload(task);
        for (int i = 0; i < params.length; i++) {
            String key = paramBindingKey(params[i]);
            if (key == null) {
                throw new IllegalArgumentException(
                        "Параметр " + (i + 1) + " метода " + method.getName() + " без аннотации @Param(\"...\")");
            }
            out[i] = valueForParamKey(
                    key,
                    task,
                    payload,
                    params[i].getType(),
                    params[i].getParameterizedType(),
                    calcClass,
                    method);
        }
        return out;
    }

    private static ParsedPayload parsePayload(UniversalTask task) {
        if (task.getTaskPayload().isEmpty()) {
            return ParsedPayload.empty();
        }
        String raw = task.getTaskPayload().toString(StandardCharsets.UTF_8);
        return ParsedPayload.parse(raw);
    }

    private static String paramBindingKey(Parameter param) {
        for (Annotation a : param.getAnnotations()) {
            String annSimple = a.annotationType().getSimpleName();
            if (!"Param".equals(annSimple)) {
                continue;
            }
            try {
                Method vm = a.annotationType().getMethod("value");
                Object v = vm.invoke(a);
                if (v instanceof String && !((String) v).isEmpty()) {
                    return (String) v;
                }
            } catch (ReflectiveOperationException ignored) {
                // no-op
            }
            try {
                Method vm = a.annotationType().getMethod("paramName");
                Object v = vm.invoke(a);
                if (v instanceof String && !((String) v).isEmpty()) {
                    return (String) v;
                }
            } catch (ReflectiveOperationException ignored) {
                // no-op
            }
        }
        return null;
    }

    private static Object valueForParamKey(
            String key,
            UniversalTask task,
            ParsedPayload payload,
            Class<?> paramType,
            Type genericType,
            Class<?> calcClass,
            Method method) throws ReflectiveOperationException, ClassNotFoundException {
        if ("taskPayload".equals(key)) {
            return task.getTaskPayload().toByteArray();
        }
        if ("taskPayloadString".equals(key)) {
            return task.getTaskPayload().toString(StandardCharsets.UTF_8);
        }
        if ("taskKind".equals(key)) {
            return task.getTaskKind();
        }
        if ("taskNumber".equals(key)) {
            return task.getTaskNumber();
        }
        String raw = payload.get(key);
        if (raw == null) {
            throw new IllegalArgumentException("В payload отсутствует ключ @" + key);
        }
        return convertValue(raw, paramType, genericType, payload, calcClass, method, key);
    }

    private static Object convertValue(String raw,
                                       Class<?> paramType,
                                       Type genericType,
                                       ParsedPayload payload,
                                       Class<?> calcClass,
                                       Method method,
                                       String key) throws ReflectiveOperationException, ClassNotFoundException {
        if (String.class.equals(paramType)) {
            return raw;
        }
        if (byte[].class.equals(paramType)) {
            return raw.getBytes(StandardCharsets.UTF_8);
        }
        if (int.class.equals(paramType) || Integer.class.equals(paramType)) {
            return Integer.parseInt(raw);
        }
        if (long.class.equals(paramType) || Long.class.equals(paramType)) {
            return Long.parseLong(raw);
        }
        if (boolean.class.equals(paramType) || Boolean.class.equals(paramType)) {
            return Boolean.parseBoolean(raw);
        }
        if (int[][].class.equals(paramType)) {
            return parseIntMatrix(raw, payload);
        }
        if (List.class.isAssignableFrom(paramType)) {
            return parseList(raw, genericType, calcClass, method);
        }
        return raw;
    }

    private static int[][] parseIntMatrix(String flatRaw, ParsedPayload payload) {
        List<Integer> flat = parseIntCsv(flatRaw);
        int size;
        String matrixSizeRaw = payload.get("matrixSize");
        if (matrixSizeRaw != null) {
            size = Integer.parseInt(matrixSizeRaw);
        } else {
            size = (int) Math.round(Math.sqrt(flat.size()));
        }
        if (size <= 0 || size * size != flat.size()) {
            throw new IllegalArgumentException("Невалидный размер матрицы: flat=" + flat.size() + ", size=" + size);
        }
        int[][] matrix = new int[size][size];
        for (int i = 0; i < size; i++) {
            for (int j = 0; j < size; j++) {
                matrix[i][j] = flat.get(i * size + j);
            }
        }
        return matrix;
    }

    private static Object parseList(String raw,
                                    Type genericType,
                                    Class<?> calcClass,
                                    Method method) throws ReflectiveOperationException, ClassNotFoundException {
        Class<?> elemClass = elementClassOf(genericType);
        if (elemClass == null) {
            elemClass = resolvePassengerClass(calcClass, method);
        }
        if (String.class.equals(elemClass)) {
            return splitCsv(raw);
        }
        if (Integer.class.equals(elemClass) || int.class.equals(elemClass)) {
            return parseIntCsv(raw);
        }
        return parseObjectList(raw, elemClass);
    }

    private static Class<?> elementClassOf(Type genericType) {
        if (!(genericType instanceof ParameterizedType)) {
            return null;
        }
        Type[] args = ((ParameterizedType) genericType).getActualTypeArguments();
        if (args.length != 1 || !(args[0] instanceof Class)) {
            return null;
        }
        return (Class<?>) args[0];
    }

    private static List<String> splitCsv(String raw) {
        if (raw == null || raw.isEmpty()) {
            return new ArrayList<>();
        }
        return new ArrayList<>(Arrays.asList(raw.split(",")));
    }

    private static List<Integer> parseIntCsv(String raw) {
        List<String> parts = splitCsv(raw);
        List<Integer> out = new ArrayList<>(parts.size());
        for (String part : parts) {
            out.add(Integer.parseInt(part));
        }
        return out;
    }

    private static List<Object> parseObjectList(String raw, Class<?> elemClass) throws ReflectiveOperationException {
        List<Object> out = new ArrayList<>();
        List<String> items = splitCsv(raw);
        List<Method> intSetters = intSetters(elemClass);
        List<Field> intFields = intFields(elemClass);

        for (String item : items) {
            Object instance = elemClass.getConstructor().newInstance();
            String[] pieces = item.split("-");
            for (int i = 0; i < pieces.length; i++) {
                int value = Integer.parseInt(pieces[i]);
                if (i < intSetters.size()) {
                    intSetters.get(i).invoke(instance, value);
                } else if (i < intFields.size()) {
                    Field f = intFields.get(i);
                    f.setAccessible(true);
                    f.setInt(instance, value);
                }
            }
            out.add(instance);
        }
        return out;
    }

    private static List<Method> intSetters(Class<?> type) {
        List<Method> setters = new ArrayList<>();
        for (Method m : type.getMethods()) {
            if (m.getName().startsWith("set")
                    && m.getParameterCount() == 1
                    && (m.getParameterTypes()[0] == int.class || m.getParameterTypes()[0] == Integer.class)) {
                setters.add(m);
            }
        }
        setters.sort(Comparator.comparing(Method::getName));
        Method setStart = null;
        Method setEnd = null;
        for (Method m : setters) {
            if ("setStartNode".equals(m.getName())) {
                setStart = m;
            } else if ("setEndNode".equals(m.getName())) {
                setEnd = m;
            }
        }
        if (setStart != null && setEnd != null) {
            return Arrays.asList(setStart, setEnd);
        }
        return setters;
    }

    private static List<Field> intFields(Class<?> type) {
        List<Field> fields = new ArrayList<>();
        for (Field f : type.getDeclaredFields()) {
            if (f.getType() == int.class || f.getType() == Integer.class) {
                fields.add(f);
            }
        }
        fields.sort(Comparator.comparing(Field::getName));
        return fields;
    }

    private Resolved resolve() throws IOException, ClassNotFoundException {
        Resolved local = resolved;
        if (local != null) {
            return local;
        }
        synchronized (this) {
            if (resolved != null) {
                return resolved;
            }
            resolved = scanJar();
            return resolved;
        }
    }

    private Resolved scanJar() throws IOException, ClassNotFoundException {
        Class<?> targetClass = null;
        try (JarFile jarFile = new JarFile(jarPath.toFile())) {
            Enumeration<JarEntry> entries = jarFile.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                String name = entry.getName();
                if (!name.endsWith(".class") || name.startsWith("META-INF")) {
                    continue;
                }
                String binary = name.substring(0, name.length() - ".class".length()).replace('/', '.');
                Class<?> c;
                try {
                    c = Class.forName(binary, false, classLoader);
                } catch (Throwable ex) {
                    continue;
                }
                if (hasAnnotationNamed(c, TARGET_CLASS_ANNOTATION)) {
                    if (targetClass != null) {
                        throw new IllegalStateException(
                                "В JAR больше одного класса с @" + TARGET_CLASS_ANNOTATION + ": "
                                        + targetClass.getName() + " и " + c.getName());
                    }
                    targetClass = c;
                }
            }
        }
        if (targetClass == null) {
            throw new ClassNotFoundException(
                    "В JAR не найден класс с аннотацией @" + TARGET_CLASS_ANNOTATION);
        }
        List<MethodRole> targetMethods = new ArrayList<>();
        Method library = null;
        for (Method m : targetClass.getMethods()) {
            String role = targetMethodRole(m);
            if (role == null) {
                continue;
            }
            if (ROLE_LIBRARY_INFO.equals(role)) {
                if (library != null) {
                    throw new IllegalStateException("Два метода с @TargetMethod(\"" + ROLE_LIBRARY_INFO + "\")");
                }
                library = m;
            } else {
                targetMethods.add(new MethodRole(m, role));
            }
        }
        if (targetMethods.isEmpty()) {
            throw new IllegalStateException("В JAR не найден метод с @" + TARGET_METHOD_ANNOTATION);
        }
        return new Resolved(targetClass, targetMethods, library);
    }

    private static String targetMethodRole(Method m) {
        for (Annotation a : m.getAnnotations()) {
            if (!TARGET_METHOD_ANNOTATION.equals(a.annotationType().getSimpleName())) {
                continue;
            }
            try {
                Method value = a.annotationType().getMethod("value");
                Object v = value.invoke(a);
                if (v instanceof String && !((String) v).isEmpty()) {
                    return (String) v;
                }
                return m.getName();
            } catch (ReflectiveOperationException e) {
                return m.getName();
            }
        }
        return null;
    }

    private static boolean hasAnnotationNamed(Class<?> c, String annotationSimpleName) {
        for (Annotation a : c.getAnnotations()) {
            if (annotationSimpleName.equals(a.annotationType().getSimpleName())) {
                return true;
            }
        }
        return false;
    }

    private static Object instantiateIfNeeded(Class<?> declaringType, Method m) throws ReflectiveOperationException {
        if (Modifier.isStatic(m.getModifiers())) {
            return null;
        }
        return declaringType.getDeclaredConstructor().newInstance();
    }

    private static Method selectTargetMethod(Resolved resolved, UniversalTask task) {
        ParsedPayload payload = parsePayload(task);
        String requested = payload.get("targetMethod");
        if (requested == null || requested.isEmpty()) {
            requested = task.getTaskKind();
        }
        if (requested != null && !requested.isEmpty()) {
            for (MethodRole mr : resolved.targetMethods) {
                if (requested.equals(mr.role) || requested.equals(mr.method.getName())) {
                    return mr.method;
                }
            }
        }
        if (resolved.targetMethods.size() == 1) {
            return resolved.targetMethods.get(0).method;
        }
        StringBuilder available = new StringBuilder();
        for (MethodRole mr : resolved.targetMethods) {
            if (available.length() > 0) {
                available.append(", ");
            }
            available.append(mr.role).append("->").append(mr.method.getName());
        }
        throw new IllegalStateException(
                "Не удалось выбрать целевой метод. Укажите taskKind/targetMethod. Доступно: " + available);
    }

    @Override
    public void close() throws Exception {
        classLoader.close();
    }

    private static final class Resolved {
        final Class<?> targetClass;
        final List<MethodRole> targetMethods;
        final Method libraryInfoMethod;

        Resolved(Class<?> targetClass, List<MethodRole> targetMethods, Method libraryInfoMethod) {
            this.targetClass = targetClass;
            this.targetMethods = targetMethods;
            this.libraryInfoMethod = libraryInfoMethod;
        }
    }

    private static final class MethodRole {
        final Method method;
        final String role;

        MethodRole(Method method, String role) {
            this.method = method;
            this.role = role;
        }
    }

    private static final class ParsedPayload {
        final Map<String, String> values;

        private ParsedPayload(Map<String, String> values) {
            this.values = values;
        }

        static ParsedPayload empty() {
            return new ParsedPayload(Collections.emptyMap());
        }

        String get(String key) {
            return values.get(key);
        }

        static ParsedPayload parse(String raw) {
            Map<String, String> map = new LinkedHashMap<>();
            for (String part : raw.split(";")) {
                String[] kv = part.split("=", 2);
                if (kv.length == 2 && !kv[0].isEmpty()) {
                    map.put(kv[0], kv[1]);
                }
            }
            return new ParsedPayload(map);
        }
    }
}
