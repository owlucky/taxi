package org.example.calculator;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;


@TargetClass
public class TaxiCostCalculator {
    private static final int AMOUNT_OF_THREADS = Math.max(1, Runtime.getRuntime().availableProcessors());

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.PARAMETER)
    public @interface Param {
        String value();
    }


    public static class Passenger {
        private int startNode;
        private int endNode;

        public Passenger() {}

        public Passenger(int startNode, int endNode) {
            this.startNode = startNode;
            this.endNode = endNode;
        }

        public int getStartNode() { return startNode; }
        public void setStartNode(int startNode) { this.startNode = startNode; }

        public int getEndNode() { return endNode; }
        public void setEndNode(int endNode) { this.endNode = endNode; }

        @Override
        public String toString() {
            return "Passenger{start=" + startNode + ", end=" + endNode + "}";
        }
    }

    public static class BatchMinResult {
        private final String bestVariant;
        private final int minCost;

        public BatchMinResult(String bestVariant, int minCost) {
            this.bestVariant = bestVariant;
            this.minCost = minCost;
        }

        public String getBestVariant() {
            return bestVariant;
        }

        public int getMinCost() {
            return minCost;
        }

        public String getResultLabel() {
            return bestVariant;
        }

        public long getScore() {
            return minCost;
        }

        public byte[] getResultPayload() {
            String payload = "{\"bestVariant\":\"" + bestVariant + "\",\"minCost\":" + minCost + "}";
            return payload.getBytes(StandardCharsets.UTF_8);
        }
    }


    public static int calculateCost(
            @Param("variant") String variant,
            @Param("taxiPositions") List<Integer> taxiPositions,
            @Param("passengers") List<Passenger> passengers,
            @Param("adjMatrix") int[][] adjMatrix) {

        if (variant == null || taxiPositions == null || passengers == null || adjMatrix == null) {
            throw new IllegalArgumentException("Arguments cannot be null");
        }

        int totalCost = 0;

        for (int i = 0; i < variant.length(); i++) {
            int taxiIndex = Character.getNumericValue(variant.charAt(i));
            if (taxiIndex >= taxiPositions.size()) {
                throw new IllegalArgumentException("Taxi index out of bounds: " + taxiIndex);
            }

            int taxiNode = taxiPositions.get(taxiIndex);
            Passenger passenger = passengers.get(i);


            if (taxiNode >= adjMatrix.length || passenger.getStartNode() >= adjMatrix.length ||
                    passenger.getEndNode() >= adjMatrix.length) {
                throw new IllegalArgumentException("Node index out of matrix bounds");
            }

            int toPassenger = adjMatrix[taxiNode][passenger.getStartNode()];
            int rideCost = adjMatrix[passenger.getStartNode()][passenger.getEndNode()];

            totalCost += toPassenger + rideCost;
        }

        return totalCost;
    }


    public static int calculateCostSimple(
            String variant,
            List<Integer> taxiPositions,
            List<Passenger> passengers,
            int[][] adjMatrix) {
        return calculateCost(variant, taxiPositions, passengers, adjMatrix);
    }


    @TargetMethod("batch")
    public static BatchMinResult calculateBatchMin(
            @Param("variants") List<String> variants,
            @Param("taxiPositions") List<Integer> taxiPositions,
            @Param("passengers") List<Passenger> passengers,
            @Param("adjMatrix") int[][] adjMatrix) {

        if (variants == null || variants.isEmpty()) {
            throw new IllegalArgumentException("Variants cannot be null or empty");
        }

        int n = variants.size();
        int threadCount = Math.min(AMOUNT_OF_THREADS, n);

        List<VariantBatchThread> threads = new ArrayList<>(threadCount);
        for (int t = 0; t < threadCount; t++) {
            int from = t * n / threadCount;
            int to = (t + 1) * n / threadCount;
            VariantBatchThread worker = new VariantBatchThread(variants, from, to, taxiPositions, passengers, adjMatrix);
            threads.add(worker);
            worker.start();
        }

        int minCost = Integer.MAX_VALUE;
        String bestVariant = "";
        for (VariantBatchThread worker : threads) {
            try {
                worker.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Interrupted while waiting cost threads", e);
            }
            if (worker.getError() != null) {
                throw new RuntimeException("Failed to compute variants in range [" + worker.getFrom() + ", " + worker.getTo() + ")", worker.getError());
            }
            if (worker.getMinCost() < minCost) {
                minCost = worker.getMinCost();
                bestVariant = worker.getBestVariant();
            }
        }
        return new BatchMinResult(bestVariant, minCost);
    }

    public static BatchMinResult calculateBatchMinSimple(
            List<String> variants,
            List<Integer> taxiPositions,
            List<Passenger> passengers,
            int[][] adjMatrix) {
        return calculateBatchMin(variants, taxiPositions, passengers, adjMatrix);
    }


    @TargetMethod("libraryInfo")
    public static String getLibraryInfo() {
        return "TaxiCostCalculator v1.2 (batch min, " + AMOUNT_OF_THREADS + " threads max)";
    }


    private static final class VariantBatchThread extends Thread {
        private final List<String> variants;
        private final int from;
        private final int to;
        private final List<Integer> taxiPositions;
        private final List<Passenger> passengers;
        private final int[][] adjMatrix;
        private String bestVariant = "";
        private int minCost = Integer.MAX_VALUE;
        private RuntimeException error;

        private VariantBatchThread(List<String> variants,
                                   int from,
                                   int to,
                                   List<Integer> taxiPositions,
                                   List<Passenger> passengers,
                                   int[][] adjMatrix) {
            this.variants = variants;
            this.from = from;
            this.to = to;
            this.taxiPositions = taxiPositions;
            this.passengers = passengers;
            this.adjMatrix = adjMatrix;
        }

        @Override
        public void run() {
            try {
                for (int i = from; i < to; i++) {
                    String variant = variants.get(i);
                    int cost = calculateCost(variant, taxiPositions, passengers, adjMatrix);
                    if (cost < minCost) {
                        minCost = cost;
                        bestVariant = variant;
                    }
                }
            } catch (RuntimeException e) {
                this.error = e;
            }
        }

        int getFrom() {
            return from;
        }

        int getTo() {
            return to;
        }

        String getBestVariant() {
            return bestVariant;
        }

        int getMinCost() {
            return minCost;
        }

        RuntimeException getError() {
            return error;
        }
    }
}