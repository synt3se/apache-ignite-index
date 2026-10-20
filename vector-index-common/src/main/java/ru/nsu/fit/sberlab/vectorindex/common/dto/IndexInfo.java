package ru.nsu.fit.sberlab.vectorindex.common.dto;

import java.io.Serializable;

/** Настроенное определение индекса; {@code fingerprint} сверяется между узлами при поиске. */
public class IndexInfo implements Serializable {

    private static final long serialVersionUID = 1L;

    public String indexId;
    public String cacheName;
    /** Поле значения с вектором; {@code null} — значение кэша само является {@code float[]}. */
    public String field;
    public int dimension;
    public String metric;
    public String algorithm;
    public String fingerprint;
    /** Загружен ли индекс на узле, который ответил (выгрузка действует до рестарта узла). */
    public boolean loaded;

    public IndexInfo() {
    }
}
