package ru.nsu.fit.vector.node.service;

import org.apache.ignite.Ignite;
import org.apache.ignite.resources.IgniteInstanceResource;
import org.apache.ignite.services.Service;
import org.apache.ignite.services.ServiceContext;

import ru.nsu.fit.vector.node.index.PartitionIndexManager;

public class LocalIndexServiceImpl implements Service {

    private static final long serialVersionUID = 1L;

    @IgniteInstanceResource
    private transient Ignite ignite;
    private transient PartitionIndexManager manager;

    @Override
    public void init(ServiceContext ctx) {
        manager = new PartitionIndexManager(ignite, "vectors");
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