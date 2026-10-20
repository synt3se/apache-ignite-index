package ru.nsu.fit.sberlab.vectorindex.core.algorithm;

/** Получатель кандидатов по локальным ordinals; точный rerank и итоговый top-k делает ядро. */
@FunctionalInterface
public interface CandidateConsumer {
    void accept(int ordinal);
}
