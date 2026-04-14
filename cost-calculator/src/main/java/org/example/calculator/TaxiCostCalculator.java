package org.example.calculator;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.List;


public class TaxiCostCalculator {

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


    public static String getLibraryInfo() {
        return "TaxiCostCalculator v1.0 (with @Param annotations)";
    }
}