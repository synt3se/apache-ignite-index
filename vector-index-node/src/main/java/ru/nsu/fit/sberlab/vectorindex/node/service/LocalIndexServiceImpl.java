package ru.nsu.fit.sberlab.vectorindex.node.service;

import org.apache.ignite.Ignite;
import org.apache.ignite.resources.IgniteInstanceResource;
import org.apache.ignite.services.Service;
import org.apache.ignite.services.ServiceContext;

import ru.nsu.fit.sberlab.vectorindex.node.index.JVectorProperties;
import ru.nsu.fit.sberlab.vectorindex.node.index.PartitionIndexManager;
import ru.nsu.fit.sberlab.vectorindex.common.indextype.IndexType;

public class LocalIndexServiceImpl implements Service {

    private static final long serialVersionUID = 1L;

    @IgniteInstanceResource
    private transient Ignite ignite;
    private transient PartitionIndexManager manager;
    private IndexType indexType = IndexType.JVECTOR_INDEX;

    private int dimension = 512;



    public void setDimension(int dimension) {
        this.dimension = dimension;
    }


    public void setIndexType(IndexType indexType) {
        this.indexType = indexType;
    }
    private JVectorProperties jvectorProperties;

    public void setJvectorProperties(JVectorProperties jvectorProperties) {
        this.jvectorProperties = jvectorProperties;
    }

    @Override
    public void init(ServiceContext ctx) {
        ignite.log().info("[vindex] service init, indexType=" + indexType);
        manager = new PartitionIndexManager(
                ignite,
                "vectors",
                dimension,
                indexType,
                jvectorProperties
        );
        manager.start();
    }

    @Override
    public void execute(ServiceContext ctx) {
        // фоновой работы нет - менеджер живёт на своих пулах
    }

    @Override
    public void cancel(ServiceContext ctx) {
        if (manager != null) manager.stop();
    }
}