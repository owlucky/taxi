package org.example;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import org.example.proto.Passenger;
import org.example.proto.InitRequest;
import org.example.proto.InitResponse;
import org.example.proto.Empty;
import org.example.proto.FinalResultResponse;
import org.example.proto.TaskFormatterGrpc;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

public class TestClient {
    public static void main(String[] args) throws InterruptedException {
        System.out.println("=== ТЕСТОВЫЙ КЛИЕНТ ===");

        ManagedChannel channel = ManagedChannelBuilder.forAddress("localhost", 8081)
                .usePlaintext()
                .build();

        TaskFormatterGrpc.TaskFormatterBlockingStub stub = TaskFormatterGrpc.newBlockingStub(channel);

        System.out.println("\n1. Отправка данных инициализации...");

        int[][] matrix = {
                {0, 10, 20},
                {10, 0, 15},
                {20, 15, 0}
        };

        java.util.ArrayList<Integer> flatMatrix = new java.util.ArrayList<>();
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                flatMatrix.add(matrix[i][j]);
            }
        }

        Passenger passenger1 = Passenger.newBuilder()
                .setStartNode(0)
                .setEndNode(2)
                .build();

        Passenger passenger2 = Passenger.newBuilder()
                .setStartNode(1)
                .setEndNode(0)
                .build();

        InitRequest initRequest = InitRequest.newBuilder()
                .addAllAdjMatrix(flatMatrix)
                .setMatrixSize(3)
                .addAllTaxiPositions(Arrays.asList(0, 1))
                .addPassengers(passenger1)
                .addPassengers(passenger2)
                .build();

        InitResponse initResponse = stub.initData(initRequest);
        System.out.println("Ответ: " + initResponse.getMessage());

        System.out.println("\n2. Ожидание финального результата...");
        Empty emptyRequest = Empty.newBuilder().build();
        long timeoutMs = 30_000L;
        long startedAt = System.currentTimeMillis();
        while (true) {
            FinalResultResponse finalResult = stub.getFinalResult(emptyRequest);
            if (finalResult.getReady()) {
                System.out.println("\n=== ФИНАЛЬНЫЙ ОТВЕТ ===");
                System.out.println("Минимальная суммарная стоимость: " + finalResult.getMinCost());
                System.out.println("Лучшая комбинация (variant): " + finalResult.getBestVariant());
                System.out.println("Номер подзадачи: " + finalResult.getBestTaskNumber());
                break;
            }
            System.out.println("Пока не готово: " + finalResult.getProcessedResults() + "/" +
                    finalResult.getTotalTasks() + " результатов");
            if (System.currentTimeMillis() - startedAt > timeoutMs) {
                System.out.println("Таймаут ожидания. Вычислители, вероятно, еще работают.");
                break;
            }
            Thread.sleep(1000);
        }

        channel.shutdown();
        channel.awaitTermination(5, TimeUnit.SECONDS);

        System.out.println("\n=== ТЕСТ ЗАВЕРШЕН ===");
    }
}