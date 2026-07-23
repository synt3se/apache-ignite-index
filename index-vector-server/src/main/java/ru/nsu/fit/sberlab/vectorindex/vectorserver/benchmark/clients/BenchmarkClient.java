package ru.nsu.fit.sberlab.vectorindex.vectorserver.benchmark.clients;

import org.apache.ignite.Ignition;
import org.apache.ignite.client.IgniteClient;
import org.apache.ignite.configuration.ClientConfiguration;
import ru.nsu.fit.sberlab.vectorindex.common.dto.Neighbor;
import ru.nsu.fit.sberlab.vectorindex.vectorserver.index.DistributedVectorIndex;

import java.util.List;

final class BenchmarkClient implements AutoCloseable {
    private final int id;
    private final IgniteClient igniteClient;
    private final DistributedVectorIndex index;

    public BenchmarkClient(int id, String igniteAddress, String cacheName, int dimension) {
        if (id < 0)
            throw new IllegalArgumentException("Client id must not be negative");

        if (igniteAddress == null || igniteAddress.isBlank())
            throw new IllegalArgumentException("Ignite address is required");

        if (cacheName == null || cacheName.isBlank())
            throw new IllegalArgumentException("Cache name is required");

        if (dimension <= 0)
            throw new IllegalArgumentException("Dimension must be positive");

        this.id = id;

        ClientConfiguration configuration = new ClientConfiguration()
                .setAddresses(igniteAddress);

        this.igniteClient = Ignition.startClient(configuration);
        this.index = new DistributedVectorIndex(
                igniteClient,
                cacheName,
                dimension
        );
    }

    int id() {
        return id;
    }

    List<Neighbor> search(float[] vector, int neighborCount) {
        return index.search(vector, neighborCount);
    }

    @Override
    public void close() {
        igniteClient.close();
    }
}