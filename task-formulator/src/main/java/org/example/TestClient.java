package org.example;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import com.google.protobuf.ByteString;
import org.example.proto.InitRequest;
import org.example.proto.InitResponse;
import org.example.proto.Empty;
import org.example.proto.FinalResultResponse;
import org.example.proto.TaskFormatterGrpc;
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

        String initPayload = "matrixSize=3;taxiPositions=0,1;passengers=0-2,1-0;adjMatrix="
                + joinInts(flatMatrix);

        InitRequest initRequest = InitRequest.newBuilder()
                .setTaskKind("taxi-min-cost")
                .setInitPayload(ByteString.copyFromUtf8(initPayload))
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
                System.out.println("Минимальная суммарная стоимость: " + finalResult.getBestScore());
                System.out.println("Лучшая комбинация (variant): " + finalResult.getBestLabel());
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

    private static String joinInts(java.util.List<Integer> values) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                sb.append(",");
            }
            sb.append(values.get(i));
        }
        return sb.toString();
    }
}