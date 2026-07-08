package ru.nsu.fit.vector.node.compute;

import org.apache.ignite.Ignite;
import org.apache.ignite.cluster.ClusterNode;
import org.apache.ignite.compute.ComputeJob;
import org.apache.ignite.compute.ComputeJobResult;
import org.apache.ignite.compute.ComputeTaskAdapter;
import org.apache.ignite.resources.IgniteInstanceResource;
import ru.nsu.fit.vector.node.compute.nodework.DeleteFromLocalIndexJob;

import java.io.Serializable;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class DeleteVectorTask
        extends ComputeTaskAdapter<DeleteVectorTask.Arg, Void> {

    private static final String CACHE_NAME = "vectors";

    @IgniteInstanceResource
    private transient Ignite ignite;

    public static class Arg implements Serializable {
        private long id;

        public Arg() {
        }

        public Arg(long id) {
            this.id = id;
        }

        public long id() {
            return id;
        }
    }

    @Override
    public Map<? extends ComputeJob, ClusterNode> map(
            List<ClusterNode> subgrid,
            Arg arg
    ) {
        ClusterNode primaryNode =
                ignite.affinity(CACHE_NAME).mapKeyToNode(arg.id());

        Map<ComputeJob, ClusterNode> jobs = new HashMap<>();

        jobs.put(
                new DeleteFromLocalIndexJob(arg.id()),
                primaryNode
        );

        return jobs;
    }

    @Override
    public Void reduce(List<ComputeJobResult> results) {
        return null;
    }
}