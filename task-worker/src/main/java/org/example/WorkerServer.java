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
                    byte[] payload = serializeResult(batchResult);
                    long dt = System.currentTimeMillis() - t0;

                    System.out.println("Подзадача #" + task.getTaskNumber()
                            + " kind=" + task.getTaskKind()
                            + " -> выполнена за " + dt + " мс");

                    UniversalResult resultReq = UniversalResult.newBuilder()
                            .setWorkerId(workerId)
                            .setTaskNumber(task.getTaskNumber())
                            .setTaskKind(task.getTaskKind())
                            .setSuccess(true)
                            .setMessage("OK")
                            .setResultLabel("")
                            .setScore(0)
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


    private static byte[] serializeResult(Object result) {
        if (result == null) {
            return new byte[0];
        }
        if (result instanceof byte[]) {
            return (byte[]) result;
        }
        if (result instanceof CharSequence || result instanceof Number || result instanceof Boolean) {
            return String.valueOf(result).getBytes(StandardCharsets.UTF_8);
        }
        String json = reflectiveJson(result);
        if (!json.isEmpty()) {
            return json.getBytes(StandardCharsets.UTF_8);
        }
        return String.valueOf(result).getBytes(StandardCharsets.UTF_8);
    }

    private static String reflectiveJson(Object obj) {
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        boolean first = true;
        for (Method m : obj.getClass().getMethods()) {
            if (m.getParameterCount() != 0) {
                continue;
            }
            if (m.getDeclaringClass() == Object.class) {
                continue;
            }
            String name = m.getName();
            if (!name.startsWith("get") || name.length() <= 3) {
                continue;
            }
            Object value;
            try {
                value = m.invoke(obj);
            } catch (Exception ignored) {
                continue;
            }
            if (!(value instanceof String) && !(value instanceof Number) && !(value instanceof Boolean)) {
                continue;
            }
            String field = Character.toLowerCase(name.charAt(3)) + name.substring(4);
            if (!first) {
                sb.append(",");
            }
            sb.append("\"").append(escapeJson(field)).append("\":");
            if (value instanceof String) {
                sb.append("\"").append(escapeJson((String) value)).append("\"");
            } else {
                sb.append(value);
            }
            first = false;
        }
        sb.append("}");
        return first ? "" : sb.toString();
    }

    private static String escapeJson(String s) {
        return s
                .replace("\\", "\\\\")
                .replace("\"", "\\\"");
    }
}
