package ru.nsu.fit.sberlab.vectorindex.node.index;

public final class NodeIndexContext {

    private static volatile PartitionIndexManager manager;

    private NodeIndexContext() {
    }

    static void register(PartitionIndexManager m) {
        manager = m;
    }

    static void unregister() {
        manager = null;
    }

    public static PartitionIndexManager manager() {
        return manager;
    }
}