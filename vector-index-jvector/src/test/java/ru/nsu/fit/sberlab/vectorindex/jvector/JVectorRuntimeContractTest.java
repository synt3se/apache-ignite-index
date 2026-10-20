package ru.nsu.fit.sberlab.vectorindex.jvector;

import ru.nsu.fit.sberlab.vectorindex.core.algorithm.VectorAlgorithm;
import ru.nsu.fit.sberlab.vectorindex.testkit.PartitionRuntimeContract;

/** Тот же контрактный набор runtime, что и для Flat; параметры графа — как в прежних тестах JVector. */
class JVectorRuntimeContractTest extends PartitionRuntimeContract {

    static JVectorAlgorithm testAlgorithm() {
        return JVectorAlgorithm.withEfMultiplier(new JVectorBuildParams(8, 32, 1.2f, 1.2f, false, true), 8);
    }

    @Override
    protected VectorAlgorithm algorithm() {
        return testAlgorithm();
    }
}
