package ru.nsu.fit.sberlab.vectorindex.core;

/** Результат проверки вектора на границе приёма. */
public sealed interface Ingest permits Ingest.Accepted, Ingest.Rejected {

    /** Вектор принят; {@code vector} — собственная копия ядра, которую больше никто не меняет. */
    record Accepted(float[] vector) implements Ingest {
    }

    /** Вектор отклонён; запись должна быть исключена из проекции и учтена в готовности. */
    record Rejected(RejectReason reason) implements Ingest {
        static final Rejected WRONG_DIMENSION = new Rejected(RejectReason.WRONG_DIMENSION);
        static final Rejected NON_FINITE = new Rejected(RejectReason.NON_FINITE);
    }
}
