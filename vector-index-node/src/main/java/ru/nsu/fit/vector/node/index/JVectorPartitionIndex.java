package ru.nsu.fit.vector.node.index;

import io.github.jbellis.jvector.graph.GraphIndexBuilder;
import io.github.jbellis.jvector.graph.GraphSearcher;
import io.github.jbellis.jvector.graph.ImmutableGraphIndex;
import io.github.jbellis.jvector.graph.ListRandomAccessVectorValues;
import io.github.jbellis.jvector.graph.RandomAccessVectorValues;
import io.github.jbellis.jvector.graph.SearchResult;
import io.github.jbellis.jvector.graph.SearchResult.NodeScore;
import io.github.jbellis.jvector.graph.similarity.BuildScoreProvider;
import io.github.jbellis.jvector.graph.similarity.DefaultSearchScoreProvider;
import io.github.jbellis.jvector.graph.similarity.SearchScoreProvider;
import io.github.jbellis.jvector.util.Bits;
import io.github.jbellis.jvector.vector.VectorSimilarityFunction;
import io.github.jbellis.jvector.vector.VectorizationProvider;
import io.github.jbellis.jvector.vector.types.VectorFloat;
import io.github.jbellis.jvector.vector.types.VectorTypeSupport;
import ru.nsu.fit.vector.common.ScoredVector;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class JVectorPartitionIndex
        implements PartitionVectorIndex, AutoCloseable {

    private static final int M = 32;
    private static final int EF_CONSTRUCTION = 100;
    private static final float NEIGHBOR_OVERFLOW = 1.2f;
    private static final float ALPHA = 1.2f;
    private static final boolean ADD_HIERARCHY = true;
    private static final boolean REFINE_FINAL_GRAPH = true;
    private static final double ADD_REBUILD_RATIO = 0.10;
    private static final double DELETE_REBUILD_RATIO = 0.20;
    private static final int MIN_ADDITIONS_BEFORE_REBUILD = 256;
    private final int dimension;

    private final ExecutorService rebuildExecutor =
            Executors.newSingleThreadExecutor(r -> {
                Thread thread = new Thread(r, "jvector-rebuild");
                thread.setDaemon(true);
                return thread;
            });



    //Для преобразования float[] в VectorFloat
    private final VectorTypeSupport vectorTypeSupport =
            VectorizationProvider.getInstance().getVectorTypeSupport();

    private final ReentrantReadWriteLock lock =
            new ReentrantReadWriteLock();

    private final Map<Long, float[]> pendingVectors = new HashMap<>();
    private final Set<Long> deletedFromGraph = new HashSet<>();

    //Текущее состояние графа (Наша реализация)
    private IndexSnapshot snapshot = IndexSnapshot.empty();
    private boolean rebuildInProgress = false;


    public JVectorPartitionIndex(int dimension) {
        if (dimension <= 0)
            throw new IllegalArgumentException("dimension must be positive");

        this.dimension = dimension;
    }

    @Override
    public void add(long id, float[] vector) {
        validateVector(vector);
        boolean rebuildRequired = false;

        lock.writeLock().lock();
        try {
            if (snapshot.containsId(id)) {
                deletedFromGraph.add(id);
            }

            pendingVectors.put(id, vector.clone());

            if (shouldRebuild() && !rebuildInProgress) {
                rebuildInProgress = true;
                rebuildRequired = true;
            }
        } finally {
            lock.writeLock().unlock();
        }

        if (rebuildRequired) {
            rebuildAsync();
        }
    }



    @Override
    public void build(Map<Long, float[]> vectors) {
        if (vectors == null) {
            throw new IllegalArgumentException("vectors are required");
        }

        Map<Long, float[]> vectorsCopy = new HashMap<>(vectors.size());

        for (Map.Entry<Long, float[]> entry : vectors.entrySet()) {
            Long id = entry.getKey();
            float[] vector = entry.getValue();

            if (id == null) {
                throw new IllegalArgumentException("vector id is required");
            }

            validateVector(vector);
            vectorsCopy.put(id, vector.clone());
        }

        lock.writeLock().lock();
        try {
            if (!snapshot.isEmpty()
                    || !pendingVectors.isEmpty()
                    || !deletedFromGraph.isEmpty()) {
                throw new IllegalStateException(
                        "build can only be called on an empty index"
                );
            }

            snapshot = buildSnapshot(vectorsCopy);
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public void delete(long id) {
        boolean startRebuild = false;

        lock.writeLock().lock();
        try {
            boolean removedFromPending = pendingVectors.remove(id) != null;
            boolean existedInSnapshot = snapshot.containsId(id);

            if (removedFromPending || existedInSnapshot) {
                deletedFromGraph.add(id);
            }

            if (shouldRebuildAfterDelete() && !rebuildInProgress) {
                rebuildInProgress = true;
                startRebuild = true;
            }
        } finally {
            lock.writeLock().unlock();
        }

        if (startRebuild) {
            rebuildAsync();
        }
    }

    @Override
    public List<ScoredVector> search(
            float[] queryVector,
            int count
    ) {
        validateVector(queryVector);

        if (count <= 0) {
            return List.of();
        }

        lock.readLock().lock();

        try {
            if (snapshot.isEmpty() && pendingVectors.isEmpty()) {
                return List.of();
            }

            Map<Long, Double> distances = new HashMap<>();

            searchGraph(queryVector, count, distances);
            searchPending(queryVector, distances);

            return distances.entrySet().stream()
                    .map(entry -> new ScoredVector(
                            entry.getKey(),
                            entry.getValue()
                    ))
                    .sorted(Comparator.comparingDouble(ScoredVector::distance))
                    .limit(count)
                    .toList();
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public void clear() {
        lock.writeLock().lock();

        try {
            IndexSnapshot oldSnapshot = snapshot;

            snapshot = IndexSnapshot.empty();
            pendingVectors.clear();
            deletedFromGraph.clear();

            closeGraph(oldSnapshot.graph());
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public int size() {
        lock.readLock().lock();
        try {
            int result = 0;

            for (Long id : snapshot.idToOrdinal().keySet()) {
                if (!deletedFromGraph.contains(id)
                        && !pendingVectors.containsKey(id)) {
                    result++;
                }
            }

            result += pendingVectors.size();
            return result;
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public void close() {
        clear();
        rebuildExecutor.shutdownNow();
    }

    private void searchGraph(
            float[] queryVector,
            int count,
            Map<Long, Double> distances
    ) {
        if (snapshot.isEmpty()) {
            return;
        }

        VectorFloat<?> query = vectorTypeSupport.createFloatVector(queryVector);

        SearchScoreProvider searchScoreProvider =
                DefaultSearchScoreProvider.exact(
                        query,
                        VectorSimilarityFunction.COSINE,
                        snapshot.vectorValues()
                );

        int rerankK = Math.min(
                snapshot.size(),
                count * 3 //Math.max(count, DEFAULT_RERANK_K)
        );

        SearchResult searchResult;

        Bits activeNodes = createActiveNodesBits();

        try (GraphSearcher searcher = new GraphSearcher(snapshot.graph())) {
            searchResult = searcher.search(
                    searchScoreProvider,
                    count,
                    rerankK,
                    0.0f,
                    0.0f,
                    activeNodes
            );
        } catch (IOException e) {
            throw new RuntimeException("Failed to search JVector graph", e);
        }

        for (NodeScore nodeScore : searchResult.getNodes()) {
            int ordinal = nodeScore.node;

            if (ordinal < 0 || ordinal >= snapshot.size()) continue;

            long id = snapshot.ordinalToId().get(ordinal);

            float[] vector = snapshot.rawVectors().get(ordinal);

            distances.put(id, cosineDistance(queryVector, vector));
        }
    }

    private void searchPending(float[] queryVector, Map<Long, Double> distances) {
        for (Map.Entry<Long, float[]> entry : pendingVectors.entrySet()) {
            distances.put(
                    entry.getKey(),
                    cosineDistance(queryVector, entry.getValue())
            );
        }
    }

    private boolean isGraphIdActive(long id) {
        return !deletedFromGraph.contains(id)
                && !pendingVectors.containsKey(id);
    }

    private Bits createActiveNodesBits() {
        return ordinal -> {
            if (ordinal < 0 || ordinal >= snapshot.size()) {
                return false;
            }

            long id = snapshot.ordinalToId().get(ordinal);
            return isGraphIdActive(id);
        };
    }

    private boolean shouldRebuild() {
        return pendingVectors.size() >= additionsBeforeRebuild()
                || shouldRebuildAfterDelete();
    }

    private int additionsBeforeRebuild() {
        if (snapshot.isEmpty()) {
            return MIN_ADDITIONS_BEFORE_REBUILD;
        }

        int relativeThreshold = (int) Math.ceil(snapshot.size() * ADD_REBUILD_RATIO);

        return Math.max(MIN_ADDITIONS_BEFORE_REBUILD, relativeThreshold);
    }

    private boolean shouldRebuildAfterDelete() {
        if (snapshot.isEmpty()) {
            return false;
        }

        return deletedFromGraph.size()
                >= snapshot.size() * DELETE_REBUILD_RATIO;
    }

    private void rebuildAsync() {
        rebuildExecutor.submit(() -> {
            boolean succeeded = false;

            try {
                rebuildInBackground();
                succeeded = true;
            } catch (RuntimeException e) {
                System.err.println("JVector background rebuild failed");
                e.printStackTrace();
            } finally {
                boolean rebuildAgain = false;

                lock.writeLock().lock();
                try {
                    rebuildInProgress = false;

                    if (succeeded && shouldRebuild()) {
                        rebuildInProgress = true;
                        rebuildAgain = true;
                    }
                } finally {
                    lock.writeLock().unlock();
                }

                if (rebuildAgain) {
                    rebuildAsync();
                }
            }
        });
    }

    private void rebuildInBackground() {
        Set<Long> includedDeleted = new HashSet<>();
        Map<Long, float[]> vectorsForBuild = new HashMap<>();
        Map<Long, float[]> includedPending = new HashMap<>();

        IndexSnapshot sourceSnapshot;

        lock.readLock().lock();
        try {
            sourceSnapshot = snapshot;
            includedDeleted.addAll(deletedFromGraph);

            for (int ordinal = 0; ordinal < sourceSnapshot.size(); ordinal++) {
                long id = sourceSnapshot.ordinalToId().get(ordinal);

                if (!deletedFromGraph.contains(id)) {
                    vectorsForBuild.put(
                            id,
                            sourceSnapshot.rawVectors().get(ordinal)
                    );
                }
            }

            for (Map.Entry<Long, float[]> entry : pendingVectors.entrySet()) {
                includedPending.put(entry.getKey(), entry.getValue());
                vectorsForBuild.put(entry.getKey(), entry.getValue());
            }
        } finally {
            lock.readLock().unlock();
        }

        IndexSnapshot newSnapshot = buildSnapshot(vectorsForBuild);

        lock.writeLock().lock();
        try {
            if (snapshot != sourceSnapshot) {
                closeGraph(newSnapshot.graph());
                return;
            }

            IndexSnapshot oldSnapshot = snapshot;
            snapshot = newSnapshot;

            for (Map.Entry<Long, float[]> entry : includedPending.entrySet()) {
                pendingVectors.remove(entry.getKey(), entry.getValue());
            }

            deletedFromGraph.removeAll(includedDeleted);

            closeGraph(oldSnapshot.graph());
        } finally {
            lock.writeLock().unlock();
        }
    }



    private IndexSnapshot buildSnapshot(Map<Long, float[]> activeVectors) {
        if (activeVectors.isEmpty()) {
            return IndexSnapshot.empty();
        }

        List<Long> ordinalToId =
                new ArrayList<>(activeVectors.size());

        List<float[]> rawVectors =
                new ArrayList<>(activeVectors.size());

        List<VectorFloat<?>> jVectorValues =
                new ArrayList<>(activeVectors.size());

        Map<Long, Integer> idToOrdinal =
                new HashMap<>(activeVectors.size());

        List<Map.Entry<Long, float[]>> entries =
                new ArrayList<>(activeVectors.entrySet());

        entries.sort(Map.Entry.comparingByKey());

        for (Map.Entry<Long, float[]> entry : entries) {
            int ordinal = ordinalToId.size();
            float[] vector = entry.getValue();

            ordinalToId.add(entry.getKey());
            rawVectors.add(vector);
            idToOrdinal.put(entry.getKey(), ordinal);

            jVectorValues.add(vectorTypeSupport.createFloatVector(vector));
        }

        RandomAccessVectorValues vectorValues =
                new ListRandomAccessVectorValues(
                        jVectorValues,
                        dimension
                );

        BuildScoreProvider buildScoreProvider =
                BuildScoreProvider.randomAccessScoreProvider(
                        vectorValues,
                        VectorSimilarityFunction.COSINE
                );

        ImmutableGraphIndex graph;

        try (GraphIndexBuilder builder =
                     new GraphIndexBuilder(
                             buildScoreProvider,
                             dimension,
                             M,
                             EF_CONSTRUCTION,
                             NEIGHBOR_OVERFLOW,
                             ALPHA,
                             ADD_HIERARCHY,
                             REFINE_FINAL_GRAPH
                     )) {
            graph = builder.build(vectorValues);
        } catch (IOException e) {
            throw new RuntimeException(
                    "Failed to build JVector graph",
                    e
            );
        }

        return new IndexSnapshot(
                graph,
                vectorValues,
                List.copyOf(ordinalToId),
                List.copyOf(rawVectors),
                Map.copyOf(idToOrdinal)
        );
    }

    private void validateVector(float[] vector) {
        if (vector == null) {
            throw new IllegalArgumentException("vector is required");
        }

        if (vector.length != dimension) {
            throw new IllegalArgumentException(
                    "incorrect vector dimension: "
                            + vector.length
                            + ", required: "
                            + dimension
            );
        }
    }

    private double cosineDistance(
            float[] first,
            float[] second
    ) {
        double dotProduct = 0.0;
        double firstNorm = 0.0;
        double secondNorm = 0.0;

        for (int i = 0; i < first.length; i++) {
            dotProduct += first[i] * second[i];
            firstNorm += first[i] * first[i];
            secondNorm += second[i] * second[i];
        }

        if (firstNorm == 0.0 || secondNorm == 0.0) {
            return 1.0;
        }

        double cosineSimilarity =
                dotProduct
                        / (Math.sqrt(firstNorm)
                        * Math.sqrt(secondNorm));

        return 1.0 - cosineSimilarity;
    }

    private void closeGraph(ImmutableGraphIndex graph) {
        if (graph == null) {
            return;
        }

        try {
            graph.close();
        } catch (IOException e) {
            throw new RuntimeException(
                    "Failed to close JVector graph",
                    e
            );
        }
    }

    private record IndexSnapshot(
            ImmutableGraphIndex graph,
            RandomAccessVectorValues vectorValues,
            List<Long> ordinalToId,
            List<float[]> rawVectors,
            Map<Long, Integer> idToOrdinal
    ) {
        private static IndexSnapshot empty() {
            return new IndexSnapshot(
                    null,
                    null,
                    List.of(),
                    List.of(),
                    Map.of()
            );
        }

        private boolean isEmpty() {
            return graph == null || ordinalToId.isEmpty();
        }

        private int size() {
            return ordinalToId.size();
        }

        private boolean containsId(long id) {
            return idToOrdinal.containsKey(id);
        }
    }
}