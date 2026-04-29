package org.example;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import org.example.proto.*;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;
import java.util.concurrent.TimeUnit;


public class WorkerServer {

    private final String workerId = "worker-" + UUID.randomUUID().toString().substring(0, 8);

    public static void main(String[] args) throws Exception {
        Path jar = null;
        String host = "localhost";
        int distributorPort = 8082;

        for (int i = 0; i < args.length; i++) {
            if ("--jar".equals(args[i]) && i + 1 < args.length) {
                jar = Paths.get(args[++i]);
            } else if ("--distributor".equals(args[i]) && i + 1 < args.length) {
                String[] hp = args[++i].split(":");
                host = hp[0];
                distributorPort = Integer.parseInt(hp[1]);
            }
        }

        if (jar == null) {
            System.err.println("Запуск: java -jar task-worker.jar --jar путь/cost-calculator-1.0-SNAPSHOT.jar [--distributor host:port]");
            System.exit(1);
        }

        new WorkerServer().run(jar, host, distributorPort);
    }

    private void run(Path jarPath, String distributorHost, int distributorPort) throws Exception {
        try (CalculatorJarInvoker invoker = new CalculatorJarInvoker(jarPath)) {
            ManagedChannel channel = ManagedChannelBuilder
                    .forAddress(distributorHost, distributorPort)
                    .usePlaintext()
                    .build();

            TaskDistributorGrpc.TaskDistributorBlockingStub distributor =
                    TaskDistributorGrpc.newBlockingStub(channel);

            System.out.println("Вычислятор " + workerId);
            System.out.println("Распределитель: " + distributorHost + ":" + distributorPort);
            System.out.println("Калькулятор (JAR): " + invoker.getLibraryInfo());

            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                channel.shutdown();
                try {
                    if (!channel.awaitTermination(3, TimeUnit.SECONDS)) {
                        channel.shutdownNow();
                    }
                } catch (InterruptedException e) {
                    channel.shutdownNow();
                    Thread.currentThread().interrupt();
                }
            }));

            boolean running = true;
            while (running) {
                try {
                    DistributorRequest req = DistributorRequest.newBuilder()
                            .setWorkerId(workerId)
                            .build();
                    UniversalTask task = distributor.requestSubtask(req);

                    if (!task.getHasTask()) {
                        Thread.sleep(400);
                        continue;
                    }

                    long t0 = System.currentTimeMillis();
                    Object batchResult = invoker.invokeBatch(task);
                    String variant = readStringResult(batchResult, "getResultLabel", "getBestVariant", "");
                    int cost = readIntResult(batchResult, "getResultCost", "getMinCost", Integer.MAX_VALUE);
                    long score = readLongResult(batchResult, "getScore", "getResultCost", "getMinCost", cost);
                    byte[] payload = readBytesResult(batchResult, "getResultPayload", "toPayloadBytes");
                    long dt = System.currentTimeMillis() - t0;

                    System.out.println("Подзадача #" + task.getTaskNumber()
                            + " kind=" + task.getTaskKind()
                            + " -> стоимость " + cost + " (label \"" + variant + "\", " + dt + " мс)");

                    UniversalResult resultReq = UniversalResult.newBuilder()
                            .setWorkerId(workerId)
                            .setTaskNumber(task.getTaskNumber())
                            .setTaskKind(task.getTaskKind())
                            .setSuccess(true)
                            .setMessage("OK")
                            .setResultLabel(variant)
                            .setScore(score)
                            .setResultPayload(com.google.protobuf.ByteString.copyFrom(payload))
                            .setComputationTimeMs(dt)
                            .build();
                    distributor.submitWorkerResult(resultReq);

                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    running = false;
                } catch (Exception e) {
                    System.err.println("Ошибка воркера: " + e.getMessage());
                    e.printStackTrace();
                    Thread.sleep(1000);
                }
            }
        }
    }


    private static String readStringResult(Object target, String preferredMethod, String fallbackMethod, String defaultValue) {
        Object value = invokeOptionalNoArg(target, preferredMethod);
        if (value == null) {
            value = invokeOptionalNoArg(target, fallbackMethod);
        }
        return value == null ? defaultValue : String.valueOf(value);
    }

    private static int readIntResult(Object target, String preferredMethod, String fallbackMethod, int defaultValue) {
        Object value = invokeOptionalNoArg(target, preferredMethod);
        if (value == null) {
            value = invokeOptionalNoArg(target, fallbackMethod);
        }
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        return defaultValue;
    }

    private static long readLongResult(Object target,
                                       String preferredMethod,
                                       String fallbackMethod1,
                                       String fallbackMethod2,
                                       long defaultValue) {
        Object value = invokeOptionalNoArg(target, preferredMethod);
        if (value == null) {
            value = invokeOptionalNoArg(target, fallbackMethod1);
        }
        if (value == null) {
            value = invokeOptionalNoArg(target, fallbackMethod2);
        }
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        return defaultValue;
    }

    private static byte[] readBytesResult(Object target, String preferredMethod, String fallbackMethod) {
        Object value = invokeOptionalNoArg(target, preferredMethod);
        if (value == null) {
            value = invokeOptionalNoArg(target, fallbackMethod);
        }
        if (value instanceof byte[]) {
            return (byte[]) value;
        }
        if (value != null) {
            return String.valueOf(value).getBytes(StandardCharsets.UTF_8);
        }
        return new byte[0];
    }

    private static Object invokeOptionalNoArg(Object target, String methodName) {
        try {
            Method m = target.getClass().getMethod(methodName);
            return m.invoke(target);
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }
}
