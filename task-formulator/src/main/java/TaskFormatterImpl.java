package org.example;

import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.stub.StreamObserver;
import org.example.proto.*;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class TaskFormatterImpl extends TaskFormatterGrpc.TaskFormatterImplBase {

    private int[][] adjMatrix;
    private List<Integer> taxiPositions;
    private List<Passenger> passengers;
    private List<String> taskVariants = new ArrayList<>();
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
        taskVariants.clear();
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
                .setMessage("Данные загружены. Всего подзадач: " + taskVariants.size())
                .build();

        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }

    @Override
    public synchronized void getNextSubtask(Empty request, StreamObserver<SubtaskResponse> responseObserver) {
        if (currentTaskIndex >= taskVariants.size()) {
            responseObserver.onNext(SubtaskResponse.newBuilder()
                    .setHasTask(false)
                    .build());
            responseObserver.onCompleted();
            return;
        }

        String variant = taskVariants.get(currentTaskIndex);
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
                .setVariant(variant)
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

        if (results.size() == taskVariants.size()) {
            System.out.println("\n=== ВСЕ ЗАДАЧИ РЕШЕНЫ ===");
            System.out.println("Найдено решений: " + results.size());

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
                .setReady(results.size() == taskVariants.size() && !taskVariants.isEmpty())
                .setProcessedResults(results.size())
                .setTotalTasks(taskVariants.size());

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
        taskVariants = allNumbers;

        System.out.println("Сгенерировано подзадач: " + taskVariants.size());
    }

    /**
     * Числа от 0 до X^Y-1 в системе счисления X, длина строки Y (ведущие нули).
     */
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
