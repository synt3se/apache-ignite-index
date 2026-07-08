package ru.nsu.fit.vector.node.compute;

import org.apache.ignite.Ignite;
import org.apache.ignite.cluster.ClusterNode;
import org.apache.ignite.compute.ComputeJob;
import org.apache.ignite.compute.ComputeJobResult;
import org.apache.ignite.compute.ComputeTaskAdapter;
import org.apache.ignite.resources.IgniteInstanceResource;
import ru.nsu.fit.vector.node.compute.nodework.AddToLocalIndexJob;

import java.io.Serializable;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class AddVectorTask
        extends ComputeTaskAdapter<AddVectorTask.Arg, Void> {

    private static final String CACHE_NAME = "vectors";

    @IgniteInstanceResource
    private transient Ignite ignite;

    public static class Arg implements Serializable {
        private long id;
        private float[] vector;

        public Arg() {
        }

        public Arg(long id, float[] vector) {
            this.id = id;
            this.vector = vector;
        }

        public long id() {
            return id;
        }

        public float[] vector() {
            return vector;
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
                new AddToLocalIndexJob(arg.id(), arg.vector()),
                primaryNode
        );

        return jobs;
    }

    @Override
    public Void reduce(List<ComputeJobResult> results) {
        return null;
    }
}