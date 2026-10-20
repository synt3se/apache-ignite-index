package ru.nsu.fit.sberlab.vectorindex.core;

import java.util.Objects;
import java.util.function.Consumer;

import ru.nsu.fit.sberlab.vectorindex.core.algorithm.VectorAlgorithm;

/**
 * Конфигурация одного runtime (эпизод владения партицией × определение индекса).
 *
 * @param onOptimizationNeeded вызывается вне замков, когда политика просит сборку и её можно
 *                             начать; обычно {@code BuildService::requestOptimization}
 */
public record RuntimeConfig(String indexId, int partition, VectorSpec spec, VectorAlgorithm algorithm,
                            MaintenancePolicy policy, Consumer<PartitionRuntime<?>> onOptimizationNeeded,
                            NanoClock clock) {

    public RuntimeConfig {
        Objects.requireNonNull(indexId, "indexId");
        Objects.requireNonNull(spec, "spec");
        Objects.requireNonNull(algorithm, "algorithm");
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(onOptimizationNeeded, "onOptimizationNeeded");
        Objects.requireNonNull(clock, "clock");
    }

    /** Ручное управление сборками, системные часы. */
    public static RuntimeConfig of(String indexId, int partition, VectorSpec spec, VectorAlgorithm algorithm) {
        return new RuntimeConfig(indexId, partition, spec, algorithm, MaintenancePolicy.MANUAL, runtime -> { },
                NanoClock.SYSTEM);
    }

    public RuntimeConfig withPolicy(MaintenancePolicy value, Consumer<PartitionRuntime<?>> listener) {
        return new RuntimeConfig(indexId, partition, spec, algorithm, value, listener, clock);
    }

    public RuntimeConfig withClock(NanoClock value) {
        return new RuntimeConfig(indexId, partition, spec, algorithm, policy, onOptimizationNeeded, value);
    }
}
