package ru.nsu.fit.sberlab.vectorindex.core;

/**
 * Запись слоя дельты: монотонная ревизия runtime и принятый вектор либо tombstone ({@code null}).
 * Запись действует, только если её ревизия больше ревизии базы ({@link State#resolve}).
 */
record Entry(long revision, float[] vector) {

    boolean live() {
        return vector != null;
    }
}
