package ru.nsu.fit.sberlab.vectorindex.node.compute;

import org.apache.ignite.cluster.ClusterNode;
import org.apache.ignite.compute.ComputeJob;
import org.apache.ignite.compute.ComputeJobResult;
import org.apache.ignite.compute.ComputeTaskAdapter;
import ru.nsu.fit.sberlab.vectorindex.common.ScoredVector;
import ru.nsu.fit.sberlab.vectorindex.node.compute.nodework.SearchLocalIndexJob;

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
        Map<Long, Double> best = new HashMap<>();
        for (ComputeJobResult result : results) {
            if (result.getException() != null) {
                continue;                                  // упавший узел пропускаем
            }
            List<ScoredVector> localResult = (List<ScoredVector>) result.getData();
            if (localResult == null) {
                continue;
            }
            for (ScoredVector sv : localResult) {
                best.merge(sv.id(), sv.distance(), Math::min);   // дедуп по id, минимальная дистанция
            }
        }
        List<ScoredVector> deduped = new ArrayList<>(best.size());
        for (Map.Entry<Long, Double> e : best.entrySet()) {
            deduped.add(new ScoredVector(e.getKey(), e.getValue()));
        }
        return topK(deduped, searchCount);
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