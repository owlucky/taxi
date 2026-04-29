package org.example;

import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.stub.StreamObserver;
import org.example.proto.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
        if (!"taxi-min-cost".equals(request.getTaskKind())) {
            responseObserver.onNext(InitResponse.newBuilder()
                    .setSuccess(false)
                    .setMessage("Unsupported task kind: " + request.getTaskKind())
                    .build());
            responseObserver.onCompleted();
            return;
        }

        currentTaskIndex = 0;
        results.clear();
        taskBatches.clear();
        bestResult = null;

        TaxiInitData initData = decodeTaxiInitData(request.getInitPayload().toString(StandardCharsets.UTF_8));
        int size = initData.matrixSize;
        adjMatrix = new int[size][size];
        for (int i = 0; i < size; i++) {
            for (int j = 0; j < size; j++) {
                adjMatrix[i][j] = initData.adjMatrixFlat.get(i * size + j);
            }
        }
        taxiPositions = initData.taxiPositions;
        passengers = initData.passengers;

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

        SubtaskResponse response = SubtaskResponse.newBuilder()
                .setHasTask(true)
                .setTaskNumber(taskIndex)
                .setTaskKind("taxi-min-cost")
                .setTaskPayload(com.google.protobuf.ByteString.copyFrom(
                        encodeTaxiTaskPayload(variants, taxiPositions, passengers, flatMatrix, adjMatrix.length),
                        StandardCharsets.UTF_8))
                .build();

        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }

    @Override
    public synchronized void submitResult(ResultRequest request, StreamObserver<ResultResponse> responseObserver) {
        ParsedWorkerResult parsed = parseWorkerResult(request);
        String resultVariant = parsed.variant;
        int resultCost = parsed.cost;
        System.out.println("Получен результат: задача " + request.getTaskNumber() +
                ", вариант " + resultVariant +
                ", стоимость " + resultCost);

        results.add(new SubtaskResult(
                request.getTaskNumber(),
                resultVariant,
                resultCost
        ));
        if (bestResult == null || resultCost < bestResult.cost) {
            bestResult = new SubtaskResult(
                    request.getTaskNumber(),
                    resultVariant,
                    resultCost
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

    private ParsedWorkerResult parseWorkerResult(ResultRequest request) {
        String variant = request.getResultLabel();
        int cost = (int) request.getScore();
        if ((!variant.isEmpty() && cost > 0) || request.getResultPayload().isEmpty()) {
            return new ParsedWorkerResult(variant, cost);
        }

        String payloadText = request.getResultPayload().toString(StandardCharsets.UTF_8);
        String payloadVariant = extractStringField(payloadText, "bestVariant", "resultLabel");
        Integer payloadCost = extractIntField(payloadText, "minCost", "resultCost", "score");

        if ((variant == null || variant.isEmpty()) && payloadVariant != null) {
            variant = payloadVariant;
        }
        if ((cost == 0 || cost == Integer.MAX_VALUE) && payloadCost != null) {
            cost = payloadCost;
        }
        return new ParsedWorkerResult(variant == null ? "" : variant, cost);
    }

    private static String extractStringField(String payload, String... keys) {
        for (String key : keys) {
            Pattern p = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*\"([^\"]*)\"");
            Matcher m = p.matcher(payload);
            if (m.find()) {
                return m.group(1);
            }
        }
        return null;
    }

    private static Integer extractIntField(String payload, String... keys) {
        for (String key : keys) {
            Pattern p = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*(-?\\d+)");
            Matcher m = p.matcher(payload);
            if (m.find()) {
                return Integer.parseInt(m.group(1));
            }
        }
        return null;
    }

    @Override
    public synchronized void getFinalResult(Empty request, StreamObserver<FinalResultResponse> responseObserver) {
        FinalResultResponse.Builder builder = FinalResultResponse.newBuilder()
                .setReady(results.size() == taskBatches.size() && !taskBatches.isEmpty())
                .setProcessedResults(results.size())
                .setTotalTasks(taskBatches.size());

        if (bestResult != null) {
            builder.setBestScore(bestResult.cost)
                    .setBestLabel(bestResult.variant)
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

    private String encodeTaxiTaskPayload(List<String> variants,
                                         List<Integer> taxiPositions,
                                         List<Passenger> passengerList,
                                         List<Integer> flatMatrix,
                                         int matrixSize) {
        StringBuilder passengersEncoded = new StringBuilder();
        for (int i = 0; i < passengerList.size(); i++) {
            Passenger p = passengerList.get(i);
            if (i > 0) {
                passengersEncoded.append(",");
            }
            passengersEncoded.append(p.getStartNode()).append("-").append(p.getEndNode());
        }

        return "variants=" + String.join(",", variants)
                + ";taxiPositions=" + joinInts(taxiPositions)
                + ";passengers=" + passengersEncoded
                + ";adjMatrix=" + joinInts(flatMatrix)
                + ";matrixSize=" + matrixSize;
    }

    private static String joinInts(List<Integer> values) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                sb.append(",");
            }
            sb.append(values.get(i));
        }
        return sb.toString();
    }

    private TaxiInitData decodeTaxiInitData(String payload) {
        String[] parts = payload.split(";");
        int matrixSize = 0;
        List<Integer> taxiPos = new ArrayList<>();
        List<Passenger> pass = new ArrayList<>();
        List<Integer> flat = new ArrayList<>();

        for (String part : parts) {
            String[] kv = part.split("=", 2);
            if (kv.length != 2) {
                continue;
            }
            switch (kv[0]) {
                case "matrixSize":
                    matrixSize = Integer.parseInt(kv[1]);
                    break;
                case "taxiPositions":
                    taxiPos = parseIntList(kv[1]);
                    break;
                case "adjMatrix":
                    flat = parseIntList(kv[1]);
                    break;
                case "passengers":
                    if (!kv[1].isEmpty()) {
                        for (String pe : kv[1].split(",")) {
                            String[] se = pe.split("-", 2);
                            Passenger p = new Passenger();
                            p.setStartNode(Integer.parseInt(se[0]));
                            p.setEndNode(Integer.parseInt(se[1]));
                            pass.add(p);
                        }
                    }
                    break;
                default:
                    break;
            }
        }
        return new TaxiInitData(matrixSize, taxiPos, pass, flat);
    }

    private static List<Integer> parseIntList(String value) {
        if (value == null || value.isEmpty()) {
            return new ArrayList<>();
        }
        List<Integer> out = new ArrayList<>();
        Arrays.stream(value.split(",")).forEach(s -> out.add(Integer.parseInt(s)));
        return out;
    }

    private static final class TaxiInitData {
        final int matrixSize;
        final List<Integer> taxiPositions;
        final List<Passenger> passengers;
        final List<Integer> adjMatrixFlat;

        private TaxiInitData(int matrixSize, List<Integer> taxiPositions, List<Passenger> passengers, List<Integer> adjMatrixFlat) {
            this.matrixSize = matrixSize;
            this.taxiPositions = taxiPositions;
            this.passengers = passengers;
            this.adjMatrixFlat = adjMatrixFlat;
        }
    }

    private static final class ParsedWorkerResult {
        final String variant;
        final int cost;

        private ParsedWorkerResult(String variant, int cost) {
            this.variant = variant;
            this.cost = cost;
        }
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
