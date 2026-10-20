package ru.nsu.fit.sberlab.vectorindex.testkit;

import ru.nsu.fit.sberlab.vectorindex.core.algorithm.VectorAlgorithm;
import ru.nsu.fit.sberlab.vectorindex.core.flat.FlatAlgorithm;

/** Контрактный набор runtime для точного перебора. */
class FlatRuntimeContractTest extends PartitionRuntimeContract {
    @Override
    protected VectorAlgorithm algorithm() {
        return FlatAlgorithm.INSTANCE;
    }
}
