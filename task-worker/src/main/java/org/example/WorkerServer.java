package org.example;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import org.example.proto.*;

import java.lang.reflect.Method;

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
                    SubtaskData task = distributor.requestSubtask(req);

                    if (!task.getHasTask() || task.getVariantsCount() == 0) {
                        Thread.sleep(400);
                        continue;
                    }

                    long t0 = System.currentTimeMillis();
                    Object batchResult = invoker.invokeBatch(task);
                    String variant = readVariantFromCalculatorResult(batchResult);
                    int cost = readCostFromCalculatorResult(batchResult);
                    long dt = System.currentTimeMillis() - t0;

                    System.out.println("Подзадача #" + task.getTaskNumber()
                            + " варианты " + task.getVariantsList()
                            + " -> стоимость " + cost + " (variant \"" + variant + "\", " + dt + " мс)");

                    ResultRequest resultReq = ResultRequest.newBuilder()
                            .setTaskNumber(task.getTaskNumber())
                            .setVariant(variant)
                            .setCost(cost)
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


    private static String readVariantFromCalculatorResult(Object batchResult) throws Exception {
        Method m = batchResult.getClass().getMethod("getBestVariant");
        return (String) m.invoke(batchResult);
    }

    private static int readCostFromCalculatorResult(Object batchResult) throws Exception {
        Method m = batchResult.getClass().getMethod("getMinCost");
        Object v = m.invoke(batchResult);
        if (v instanceof Integer) {
            return (Integer) v;
        }
        return ((Number) v).intValue();
    }
}
