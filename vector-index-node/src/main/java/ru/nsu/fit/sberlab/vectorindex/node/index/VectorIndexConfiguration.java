package ru.nsu.fit.sberlab.vectorindex.node.index;

import java.io.Serializable;

import ru.nsu.fit.sberlab.vectorindex.core.Metric;

/**
 * Статическое определение индекса для XML узла (одинаковое на всех узлах):
 *
 * <pre>{@code
 * <bean class="ru.nsu.fit.sberlab.vectorindex.node.index.VectorIndexConfiguration">
 *     <property name="name" value="images"/>
 *     <property name="cacheName" value="vectors"/>
 *     <property name="field" value="vector"/>        <!-- пусто: значение кэша — сам float[] -->
 *     <property name="dimension" value="512"/>
 *     <property name="metric" value="COSINE"/>        <!-- COSINE | SQUARED_L2 -->
 *     <property name="algorithm" value="JVECTOR"/>    <!-- JVECTOR | FLAT -->
 *     <property name="jvectorProperties"> ... </property>  <!-- сборка JVector и пороги пересборки -->
 * </bean>
 * }</pre>
 */
public class VectorIndexConfiguration implements Serializable {

    private static final long serialVersionUID = 1L;

    private String name;
    private String cacheName;
    private String field;
    private int dimension;
    private Metric metric = Metric.COSINE;
    private AlgorithmKind algorithm = AlgorithmKind.JVECTOR;
    private JVectorProperties jvectorProperties;

    public VectorIndexConfiguration() {
    }

    /** Проверяет настройки и строит определение. */
    public IndexDefinition toDefinition() {
        return IndexDefinition.of(name, cacheName, field, dimension, metric, algorithm, jvectorProperties);
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getCacheName() {
        return cacheName;
    }

    public void setCacheName(String cacheName) {
        this.cacheName = cacheName;
    }

    public String getField() {
        return field;
    }

    public void setField(String field) {
        this.field = field;
    }

    public int getDimension() {
        return dimension;
    }

    public void setDimension(int dimension) {
        this.dimension = dimension;
    }

    public Metric getMetric() {
        return metric;
    }

    public void setMetric(Metric metric) {
        this.metric = metric;
    }

    public AlgorithmKind getAlgorithm() {
        return algorithm;
    }

    public void setAlgorithm(AlgorithmKind algorithm) {
        this.algorithm = algorithm;
    }

    public JVectorProperties getJvectorProperties() {
        return jvectorProperties;
    }

    public void setJvectorProperties(JVectorProperties jvectorProperties) {
        this.jvectorProperties = jvectorProperties;
    }
}
