package ru.nsu.fit.sberlab.vectorindex.node.service;

import java.util.ArrayList;
import java.util.List;

import org.apache.ignite.Ignite;
import org.apache.ignite.resources.IgniteInstanceResource;
import org.apache.ignite.services.Service;
import org.apache.ignite.services.ServiceContext;

import ru.nsu.fit.sberlab.vectorindex.common.indextype.IndexType;
import ru.nsu.fit.sberlab.vectorindex.node.index.IndexDefinition;
import ru.nsu.fit.sberlab.vectorindex.node.index.JVectorProperties;
import ru.nsu.fit.sberlab.vectorindex.node.index.NodeIndexRegistry;
import ru.nsu.fit.sberlab.vectorindex.node.index.VectorIndexConfiguration;

/**
 * Node-singleton индексной плоскости: реестр индексов узла ({@link NodeIndexRegistry}).
 * Определения — свойство {@code indexes} (статические, одинаковые на узлах). Без него действует
 * прежняя конфигурация: один индекс {@code vectors} по полю {@code vector} из {@code indexType},
 * {@code dimension} и {@code jvectorProperties}.
 */
public class LocalIndexServiceImpl implements Service {

    private static final long serialVersionUID = 2L;

    @IgniteInstanceResource
    private transient Ignite ignite;
    private transient NodeIndexRegistry registry;

    private IndexType indexType = IndexType.JVECTOR_INDEX;
    private int dimension = 512;
    private JVectorProperties jvectorProperties;
    private List<VectorIndexConfiguration> indexes;

    public void setDimension(int dimension) {
        this.dimension = dimension;
    }

    public void setIndexType(IndexType indexType) {
        this.indexType = indexType;
    }

    public void setJvectorProperties(JVectorProperties jvectorProperties) {
        this.jvectorProperties = jvectorProperties;
    }

    public void setIndexes(List<VectorIndexConfiguration> indexes) {
        this.indexes = indexes == null ? null : new ArrayList<>(indexes);
    }

    public List<VectorIndexConfiguration> getIndexes() {
        return indexes;
    }

    /** Определения, которые сервис загрузит на узле; ошибки настроек — {@link IllegalArgumentException}. */
    public List<IndexDefinition> definitions() {
        if (indexes == null || indexes.isEmpty()) {
            return List.of(IndexDefinition.legacy(dimension, indexType, jvectorProperties));
        }
        List<IndexDefinition> out = new ArrayList<>(indexes.size());
        for (VectorIndexConfiguration configuration : indexes) {
            out.add(configuration.toDefinition());
        }
        return out;
    }

    @Override
    public void init(ServiceContext ctx) {
        List<IndexDefinition> definitions = definitions();
        ignite.log().info("[vindex] service init, indexes=" + definitions.stream().map(IndexDefinition::indexId).toList());
        registry = NodeIndexRegistry.start(ignite, definitions);
    }

    @Override
    public void execute(ServiceContext ctx) {
        // фоновой работы нет - индексы живут на общих пулах реестра
    }

    @Override
    public void cancel(ServiceContext ctx) {
        if (registry != null) {
            registry.close();
        }
    }
}
