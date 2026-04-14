package org.example.calculator;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.ArrayList;
import java.util.List;


public class TaxiCostCalculator {
    private static final int DEFAULT_COST = Integer.MAX_VALUE;

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


    public static BatchMinResult calculateBatchMin(
            @Param("variants") List<String> variants,
            @Param("taxiPositions") List<Integer> taxiPositions,
            @Param("passengers") List<Passenger> passengers,
            @Param("adjMatrix") int[][] adjMatrix) {

        if (variants == null || variants.isEmpty()) {
            throw new IllegalArgumentException("Variants cannot be null or empty");
        }

        List<VariantCostThread> threads = new ArrayList<>(variants.size());
        for (String variant : variants) {
            VariantCostThread t = new VariantCostThread(variant, taxiPositions, passengers, adjMatrix);
            threads.add(t);
            t.start();
        }

        int minCost = Integer.MAX_VALUE;
        String bestVariant = "";
        for (VariantCostThread t : threads) {
            try {
                t.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Interrupted while waiting cost threads", e);
            }
            if (t.getError() != null) {
                throw new RuntimeException("Failed to compute variant " + t.getVariant(), t.getError());
            }
            if (t.getCost() < minCost) {
                minCost = t.getCost();
                bestVariant = t.getVariant();
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


    public static String getLibraryInfo() {
        return "TaxiCostCalculator v1.1 (batch min in calculator)";
    }

    private static final class VariantCostThread extends Thread {
        private final String variant;
        private final List<Integer> taxiPositions;
        private final List<Passenger> passengers;
        private final int[][] adjMatrix;
        private int cost = DEFAULT_COST;
        private RuntimeException error;

        private VariantCostThread(String variant,
                                  List<Integer> taxiPositions,
                                  List<Passenger> passengers,
                                  int[][] adjMatrix) {
            this.variant = variant;
            this.taxiPositions = taxiPositions;
            this.passengers = passengers;
            this.adjMatrix = adjMatrix;
        }

        @Override
        public void run() {
            try {
                this.cost = calculateCost(variant, taxiPositions, passengers, adjMatrix);
            } catch (RuntimeException e) {
                this.error = e;
            }
        }

        public String getVariant() {
            return variant;
        }

        public int getCost() {
            return cost;
        }

        public RuntimeException getError() {
            return error;
        }
    }
}