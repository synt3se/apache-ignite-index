package ru.nsu.fit.vectorserver.benchmark;

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

    public double percentile(double percentile) {
        List<Double> sorted = new ArrayList<>(valuesMs);
        sorted.sort(Comparator.naturalOrder());

        int index = (int) Math.ceil(percentile * sorted.size()) - 1;
        index = Math.max(0, Math.min(index, sorted.size() - 1));

        return sorted.get(index);
    }

    public int count() {
        return valuesMs.size();
    }
}