package ru.nsu.fit.vector.node.index;

import org.apache.ignite.Ignite;

public final class NodeLocalVectorIndexHolder {

    private static final String CACHE_NAME = "vectors";
    private static volatile NodeLocalVectorIndex index;

    private NodeLocalVectorIndexHolder() {
    }

    public static NodeLocalVectorIndex getOrCreate(Ignite ignite) {
        if (index == null) {
            synchronized (NodeLocalVectorIndexHolder.class) {
                if (index == null) {
                    index = new NodeLocalVectorIndex(ignite, CACHE_NAME);
                }
            }
        }

        return index;
    }

    public static void clear() {
        if (index != null) {
            index.clearLocal();
        }
    }
}