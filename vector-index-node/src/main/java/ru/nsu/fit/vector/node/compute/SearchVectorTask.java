package ru.nsu.fit.vector.node.compute;

import org.apache.ignite.cluster.ClusterNode;
import org.apache.ignite.compute.ComputeJob;
import org.apache.ignite.compute.ComputeJobResult;
import org.apache.ignite.compute.ComputeTaskAdapter;
import ru.nsu.fit.vector.common.ScoredVector;
import ru.nsu.fit.vector.node.compute.nodework.SearchLocalIndexJob;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

public class SearchVectorTask
        extends ComputeTaskAdapter<SearchVectorTask.Arg, List<ScoredVector>> {

    private int searchCount;

    public static class Arg implements Serializable {
        private float[] queryVector;
        private int searchCount;

        public Arg() {
        }

        public Arg(float[] queryVector, int searchCount) {
            this.queryVector = queryVector;
            this.searchCount = searchCount;
        }

        public float[] queryVector() {
            return queryVector;
        }

        public int searchCount() {
            return searchCount;
        }
    }

    @Override
    public Map<? extends ComputeJob, ClusterNode> map(
            List<ClusterNode> subgrid,
            Arg arg
    ) {
        searchCount = arg.searchCount();

        Map<ComputeJob, ClusterNode> jobs = new HashMap<>();

        for (ClusterNode node : subgrid) {
            jobs.put(
                    new SearchLocalIndexJob(arg.queryVector(), arg.searchCount()),
                    node
            );
        }

        return jobs;
    }

    @Override
    public List<ScoredVector> reduce(List<ComputeJobResult> results) {
        List<ScoredVector> candidates = new ArrayList<>();

        for (ComputeJobResult result : results) {
            @SuppressWarnings("unchecked")
            List<ScoredVector> localResult = (List<ScoredVector>) result.getData();
            candidates.addAll(localResult);
        }

        return topK(candidates, searchCount);
    }

    private List<ScoredVector> topK(
            Iterable<ScoredVector> candidates,
            int count
    ) {
        PriorityQueue<ScoredVector> top = new PriorityQueue<>(
                Comparator.comparingDouble(ScoredVector::distance).reversed()
        );

        for (ScoredVector candidate : candidates) {
            if (top.size() < count) {
                top.add(candidate);
            } else if (top.peek() != null
                    && candidate.distance() < top.peek().distance()) {
                top.poll();
                top.add(candidate);
            }
        }

        List<ScoredVector> result = new ArrayList<>(top);
        result.sort(Comparator.comparingDouble(ScoredVector::distance));

        return result;
    }
}