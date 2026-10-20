package ru.nsu.fit.sberlab.vectorindex.node.search;

import java.util.LinkedHashMap;
import java.util.Map;

import org.apache.ignite.Ignite;
import org.apache.ignite.cache.affinity.Affinity;
import org.apache.ignite.cluster.ClusterNode;

/** Назначение запроса по affinity координатора: серверный узел → его primary-партиции. */
public final class IgniteSearchTopology implements SearchTopology {

    private final Ignite ignite;
    private final String cacheName;

    public IgniteSearchTopology(Ignite ignite, String cacheName) {
        this.ignite = ignite;
        this.cacheName = cacheName;
    }

    @Override
    public Snapshot snapshot() {
        Affinity<Object> affinity = ignite.affinity(cacheName);
        Map<String, int[]> assignment = new LinkedHashMap<>();
        for (ClusterNode node : ignite.cluster().forServers().nodes()) {
            assignment.put(node.id().toString(), affinity.primaryPartitions(node));
        }
        return new Snapshot(affinity.partitions(), assignment, ignite.cluster().topologyVersion());
    }
}
