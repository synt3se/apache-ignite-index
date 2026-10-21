package ru.nsu.fit.sberlab.vectorindex.vectorserver.rest;

/** Тело {@code PUT /indexes/{id}/vectors}: внешние ID и векторы в одном порядке. */
public record VectorBatchBody(long[] ids, float[][] vectors) {
}
