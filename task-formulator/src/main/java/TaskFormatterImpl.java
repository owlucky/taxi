package org.example;

import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.stub.StreamObserver;
import org.example.proto.*;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class TaskFormatterImpl extends TaskFormatterGrpc.TaskFormatterImplBase {
    private static final int TASK_BATCH_SIZE = 3;

    private int[][] adjMatrix;
    private List<Integer> taxiPositions;
    private List<Passenger> passengers;
    private List<List<String>> taskBatches = new ArrayList<>();
    private int currentTaskIndex = 0;
    private List<SubtaskResult> results = new ArrayList<>();
    private SubtaskResult bestResult = null;

    static class SubtaskResult {
        int taskNumber;
        String variant;
        int cost;

        SubtaskResult(int taskNumber, String variant, int cost) {
            this.taskNumber = taskNumber;
            this.variant = variant;
            this.cost = cost;
        }
    }

    @Override
    public void initData(InitRequest request, StreamObserver<InitResponse> responseObserver) {
        System.out.println("Получены начальные данные...");

        currentTaskIndex = 0;
        results.clear();
        taskBatches.clear();
        bestResult = null;

        int size = request.getMatrixSize();
        adjMatrix = new int[size][size];
        List<Integer> flatMatrix = request.getAdjMatrixList();

        for (int i = 0; i < size; i++) {
            for (int j = 0; j < size; j++) {
                adjMatrix[i][j] = flatMatrix.get(i * size + j);
            }
        }

        taxiPositions = new ArrayList<>(request.getTaxiPositionsList());

        passengers = new ArrayList<>();
        for (org.example.proto.Passenger p : request.getPassengersList()) {
            Passenger passenger = new Passenger();
            passenger.setStartNode(p.getStartNode());
            passenger.setEndNode(p.getEndNode());
            passengers.add(passenger);
        }

        generateAllTasks();

        InitResponse response = InitResponse.newBuilder()
                .setSuccess(true)
                .setMessage("Данные загружены. Всего подзадач (батчей): " + taskBatches.size())
                .build();

        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }

    @Override
    public synchronized void getNextSubtask(Empty request, StreamObserver<SubtaskResponse> responseObserver) {
        if (currentTaskIndex >= taskBatches.size()) {
            responseObserver.onNext(SubtaskResponse.newBuilder()
                    .setHasTask(false)
                    .build());
            responseObserver.onCompleted();
            return;
        }

        List<String> variants = taskBatches.get(currentTaskIndex);
        int taskIndex = currentTaskIndex;
        currentTaskIndex++;

        List<Integer> flatMatrix = new ArrayList<>();
        for (int i = 0; i < adjMatrix.length; i++) {
            for (int j = 0; j < adjMatrix[i].length; j++) {
                flatMatrix.add(adjMatrix[i][j]);
            }
        }

        List<org.example.proto.Passenger> protoPassengers = new ArrayList<>();
        for (Passenger p : passengers) {
            protoPassengers.add(org.example.proto.Passenger.newBuilder()
                    .setStartNode(p.getStartNode())
                    .setEndNode(p.getEndNode())
                    .build());
        }

        SubtaskResponse response = SubtaskResponse.newBuilder()
                .setHasTask(true)
                .addAllVariants(variants)
                .setTaskNumber(taskIndex)
                .addAllTaxiPositions(taxiPositions)
                .addAllPassengers(protoPassengers)
                .addAllAdjMatrix(flatMatrix)
                .setMatrixSize(adjMatrix.length)
                .build();

        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }

    @Override
    public synchronized void submitResult(ResultRequest request, StreamObserver<ResultResponse> responseObserver) {
        System.out.println("Получен результат: задача " + request.getTaskNumber() +
                ", вариант " + request.getVariant() +
                ", стоимость " + request.getCost());

        results.add(new SubtaskResult(
                request.getTaskNumber(),
                request.getVariant(),
                request.getCost()
        ));
        if (bestResult == null || request.getCost() < bestResult.cost) {
            bestResult = new SubtaskResult(
                    request.getTaskNumber(),
                    request.getVariant(),
                    request.getCost()
            );
        }

        if (results.size() == taskBatches.size()) {
            System.out.println("\n=== ВСЕ ЗАДАЧИ РЕШЕНЫ ===");
            System.out.println("Найдено решений (по батчам): " + results.size());

            if (bestResult != null) {
                System.out.println("ОТВЕТ: минимальная суммарная стоимость = " + bestResult.cost);
                System.out.println("Лучшее назначение (позиция пассажира → индекс такси в base-X): вариант \"" +
                        bestResult.variant + "\" (номер подзадачи " + bestResult.taskNumber + ")");
            }
        }

        ResultResponse response = ResultResponse.newBuilder()
                .setAccepted(true)
                .build();

        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }

    @Override
    public synchronized void getFinalResult(Empty request, StreamObserver<FinalResultResponse> responseObserver) {
        FinalResultResponse.Builder builder = FinalResultResponse.newBuilder()
                .setReady(results.size() == taskBatches.size() && !taskBatches.isEmpty())
                .setProcessedResults(results.size())
                .setTotalTasks(taskBatches.size());

        if (bestResult != null) {
            builder.setMinCost(bestResult.cost)
                    .setBestVariant(bestResult.variant)
                    .setBestTaskNumber(bestResult.taskNumber);
        }

        responseObserver.onNext(builder.build());
        responseObserver.onCompleted();
    }

    private void generateAllTasks() {
        System.out.println("Генерация всех возможных комбинаций (X^Y, X=такси, Y=пассажиры)...");
        int taxiCount = taxiPositions.size();
        int passengerCount = passengers.size();

        List<String> allNumbers = generateNumbers(taxiCount, passengerCount);
        taskBatches = splitIntoBatches(allNumbers, TASK_BATCH_SIZE);

        System.out.println("Сгенерировано комбинаций: " + allNumbers.size());
        System.out.println("Сгенерировано подзадач-батчей по " + TASK_BATCH_SIZE + ": " + taskBatches.size());
    }


    private List<String> generateNumbers(int base, int length) {
        if (base < 2 || base > 36) {
            throw new IllegalArgumentException("Число такси (основание) должно быть от 2 до 36");
        }
        long total = (long) Math.pow(base, length);
        if (total > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Слишком много комбинаций (X^Y > " + Integer.MAX_VALUE + ")");
        }
        List<String> result = new ArrayList<>((int) total);

        for (int i = 0; i < total; i++) {
            result.add(toBase(i, base, length));
        }
        return result;
    }

    private String toBase(int value, int base, int length) {
        String s = Integer.toString(value, base);
        if (s.length() > length) {
            throw new IllegalStateException("value " + value + " не помещается в " + length + " разрядов");
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < length - s.length(); i++) {
            sb.append('0');
        }
        sb.append(s);
        return sb.toString();
    }

    private List<List<String>> splitIntoBatches(List<String> variants, int batchSize) {
        List<List<String>> batches = new ArrayList<>();
        for (int i = 0; i < variants.size(); i += batchSize) {
            int end = Math.min(i + batchSize, variants.size());
            batches.add(new ArrayList<>(variants.subList(i, end)));
        }
        return batches;
    }

    public static void main(String[] args) throws IOException, InterruptedException {
        Server server = ServerBuilder.forPort(8081)
                .addService(new TaskFormatterImpl())
                .build()
                .start();

        System.out.println("Формирователь задач запущен на порту 8081");

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("Останавливаем сервер...");
            server.shutdown();
        }));

        server.awaitTermination();
    }
}
