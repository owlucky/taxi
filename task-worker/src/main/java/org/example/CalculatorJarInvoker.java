package org.example;

import org.example.proto.Passenger;
import org.example.proto.SubtaskData;

import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;


public final class CalculatorJarInvoker implements AutoCloseable {

    private final URLClassLoader classLoader;

    public CalculatorJarInvoker(Path jarPath) throws Exception {
        if (!Files.isRegularFile(jarPath)) {
            throw new IllegalArgumentException("Файл JAR не найден: " + jarPath.toAbsolutePath());
        }
        this.classLoader = new URLClassLoader(
                new URL[]{jarPath.toUri().toURL()},
                ClassLoader.getSystemClassLoader().getParent());
    }

    public String getLibraryInfo() {
        try {
            Class<?> clazz = Class.forName("org.example.calculator.TaxiCostCalculator", true, classLoader);
            Method m = clazz.getMethod("getLibraryInfo");
            return (String) m.invoke(null);
        } catch (Exception e) {
            return "ошибка getLibraryInfo: " + e.getMessage();
        }
    }

    public int compute(SubtaskData task, String variant) throws Exception {
        Class<?> calcClass = Class.forName("org.example.calculator.TaxiCostCalculator", true, classLoader);
        Class<?> passClass = Class.forName("org.example.calculator.TaxiCostCalculator$Passenger", true, classLoader);

        int size = task.getMatrixSize();
        int[][] adjMatrix = new int[size][size];
        List<Integer> flatMatrix = task.getAdjMatrixList();
        for (int i = 0; i < size; i++) {
            for (int j = 0; j < size; j++) {
                adjMatrix[i][j] = flatMatrix.get(i * size + j);
            }
        }

        List<Object> passengers = new ArrayList<>();
        for (Passenger p : task.getPassengersList()) {
            Object pass = passClass.getConstructor().newInstance();
            passClass.getMethod("setStartNode", int.class).invoke(pass, p.getStartNode());
            passClass.getMethod("setEndNode", int.class).invoke(pass, p.getEndNode());
            passengers.add(pass);
        }

        List<Integer> taxiPositions = task.getTaxiPositionsList();

        Method target = findCalculateMethod(calcClass);
        return (int) target.invoke(null, variant, taxiPositions, passengers, adjMatrix);
    }

    private static Method findCalculateMethod(Class<?> calcClass) throws NoSuchMethodException {
        for (Method method : calcClass.getMethods()) {
            if (!"calculateCostSimple".equals(method.getName()) || method.getParameterCount() != 4) {
                continue;
            }
            Class<?>[] pt = method.getParameterTypes();
            if (pt[0] == String.class
                    && List.class.isAssignableFrom(pt[1])
                    && List.class.isAssignableFrom(pt[2])
                    && pt[3] == int[][].class) {
                return method;
            }
        }
        for (Method method : calcClass.getMethods()) {
            if (!"calculateCost".equals(method.getName()) || method.getParameterCount() != 4) {
                continue;
            }
            Class<?>[] pt = method.getParameterTypes();
            if (pt[0] == String.class
                    && List.class.isAssignableFrom(pt[1])
                    && List.class.isAssignableFrom(pt[2])
                    && pt[3] == int[][].class) {
                return method;
            }
        }
        throw new NoSuchMethodException(
                "В JAR нужен статический calculateCostSimple(String, List, List, int[][]) или calculateCost с теми же типами");
    }

    @Override
    public void close() throws Exception {
        classLoader.close();
    }
}
