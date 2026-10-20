package ru.nsu.fit.sberlab.vectorindex.node.index;

import ru.nsu.fit.sberlab.vectorindex.common.indextype.IndexType;

/** Алгоритм индекса в конфигурации узла. */
public enum AlgorithmKind {
    FLAT,
    JVECTOR;

    static AlgorithmKind fromLegacy(IndexType type) {
        return type == IndexType.JVECTOR_INDEX ? JVECTOR : FLAT;
    }

    /** Прежнее имя движка в {@code NodeStats.engine}. */
    String legacyEngineName() {
        return this == JVECTOR ? IndexType.JVECTOR_INDEX.name() : IndexType.BRUTE_FORCE_INDEX.name();
    }
}
