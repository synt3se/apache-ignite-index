package ru.nsu.fit.sberlab.vectorindex.core;

/** Почему принятый из источника вектор не может войти в проекцию индекса. */
public enum RejectReason {
    /** Длина вектора не совпадает с размерностью определения индекса. */
    WRONG_DIMENSION,
    /** В векторе есть NaN или бесконечность. */
    NON_FINITE
}
