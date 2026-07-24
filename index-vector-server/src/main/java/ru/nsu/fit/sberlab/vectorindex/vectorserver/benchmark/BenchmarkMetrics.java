package ru.nsu.fit.sberlab.vectorindex.vectorserver.benchmark;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class BenchmarkMetrics {
    private final List<Double> valuesMs = new ArrayList<>();

    public void add(double valueMs){
        this.valuesMs.add(valueMs);
    }

    public double average() {
        double sum = 0.0;

        for (double value : valuesMs) {
            sum += value;
        }

        return sum / valuesMs.size();
    }

    public List<Double> values() {
        return List.copyOf(valuesMs);
    }

    public double percentile(double percentile) {
        List<Double> sorted = new ArrayList<>(valuesMs);
        sorted.sort(Comparator.naturalOrder());

        int index = (int) Math.ceil(percentile * sorted.size()) - 1;
        index = Math.max(0, Math.min(index, sorted.size() - 1));

        return sorted.get(index);
    }

    public double min() {
        if (valuesMs.isEmpty()) return 0.0;

        double min = Double.MAX_VALUE;

        for (double value : valuesMs) {
            min = Math.min(min, value);
        }

        return min;
    }

    public double max() {
        if (valuesMs.isEmpty()) return 0.0;

        double max = -Double.MAX_VALUE;

        for (double value : valuesMs) {
            max = Math.max(max, value);
        }

        return max;
    }

    public int count() {
        return valuesMs.size();
    }
}