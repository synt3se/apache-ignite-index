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
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class JVectorPartitionIndex implements PartitionVectorIndex {
    /// M - количество связей у каждой вершины
    /// EF_C Ширина поиска (улучшить точность)
    /// Neighbor_overflow Запас для более быстрой работы
    /// alpha - Позволяет оставить более длинных соседей
    ///     дополнительно чтобы не уходить в плотность
    /// ADD_hie... - многоуровневость графа
    ///  REFINE_FINAL - второй проход более грамотного перестроения индекса

    private static final int M = 32;
    private static final int EF_CONSTRUCTION = 100;
    private static final float NEIGHBOR_OVERFLOW = 1.2f;
    private static final float ALPHA = 1.2f;
    private static final boolean ADD_HIERARCHY = true;
    private static final boolean REFINE_FINAL_GRAPH = true;

    private final int dimension;
    private final Map<Long, float[]> vectors = new ConcurrentHashMap<>();
    private final VectorTypeSupport vectorTypeSupport =
            VectorizationProvider.getInstance().getVectorTypeSupport();

    private volatile List<Long> ordinalToId = List.of();
    private volatile RandomAccessVectorValues randomAccessVectorValues;
    private volatile ImmutableGraphIndex graph;
    private volatile boolean dirty = true;

    public JVectorPartitionIndex(int dimension) {
        if (dimension <= 0) {
            throw new IllegalArgumentException("dimension must be positive");
        }

        this.dimension = dimension;
    }

    @Override
    public synchronized void add(long id, float[] vector) {
        validateVector(vector);
        vectors.put(id, vector);
        dirty = true;
    }

    @Override
    public synchronized void delete(long id) {
        vectors.remove(id);
        dirty = true;
    }

    @Override
    public List<ScoredVector> search(float[] queryVector, int count) {
        validateVector(queryVector);

        if (count <= 0 || vectors.isEmpty()) {
            return List.of();
        }

        rebuildIfNeeded();

        ImmutableGraphIndex localGraph = graph;
        RandomAccessVectorValues localRandomAccessVectorValues = randomAccessVectorValues;
        List<Long> localOrdinalToId = ordinalToId;

        if (localGraph == null
                || localRandomAccessVectorValues == null
                || localOrdinalToId.isEmpty()) {
            return List.of();
        }

        VectorFloat<?> query = vectorTypeSupport.createFloatVector(queryVector);

        SearchScoreProvider searchScoreProvider =
                DefaultSearchScoreProvider.exact(
                        query,
                        VectorSimilarityFunction.EUCLIDEAN,
                        localRandomAccessVectorValues
                );

        int candidateCount = Math.min(
                Math.max(count * 3, count),
                localOrdinalToId.size()
        );

        SearchResult searchResult;

        try (GraphSearcher searcher = new GraphSearcher(localGraph)) {
            searchResult = searcher.search(
                    searchScoreProvider,
                    candidateCount,
                    Bits.ALL
            );
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        List<ScoredVector> result = new ArrayList<>();

        for (NodeScore nodeScore : searchResult.getNodes()) {
            int ordinal = nodeScore.node;

            if (ordinal < 0 || ordinal >= localOrdinalToId.size()) {
                continue;
            }

            long id = localOrdinalToId.get(ordinal);
            float[] vector = vectors.get(id);

            if (vector == null) {
                continue;
            }

            result.add(new ScoredVector(
                    id,
                    euclideanDistance(queryVector, vector)
            ));
        }

        result.sort(Comparator.comparingDouble(ScoredVector::distance));

        if (result.size() > count) {
            return new ArrayList<>(result.subList(0, count));
        }

        return result;
    }

    @Override
    public synchronized void clear() {
        vectors.clear();
        ordinalToId = List.of();
        randomAccessVectorValues = null;
        graph = null;
        dirty = false;
    }

    private synchronized void rebuildIfNeeded() {
        if (!dirty) {
            return;
        }

        List<Map.Entry<Long, float[]>> entries =
                new ArrayList<>(vectors.entrySet());

        if (entries.isEmpty()) {
            ordinalToId = List.of();
            randomAccessVectorValues = null;
            graph = null;
            dirty = false;
            return;
        }

        List<Long> newOrdinalToId = new ArrayList<>(entries.size());
        List<VectorFloat<?>> baseVectors = new ArrayList<>(entries.size());

        for (Map.Entry<Long, float[]> entry : entries) {
            newOrdinalToId.add(entry.getKey());
            baseVectors.add(vectorTypeSupport.createFloatVector(entry.getValue()));
        }

        RandomAccessVectorValues newRandomAccessVectorValues =
                new ListRandomAccessVectorValues(baseVectors, dimension);

        BuildScoreProvider buildScoreProvider =
                BuildScoreProvider.randomAccessScoreProvider(
                        newRandomAccessVectorValues,
                        VectorSimilarityFunction.EUCLIDEAN
                );

        ImmutableGraphIndex newGraph;

        try (GraphIndexBuilder builder = new GraphIndexBuilder(
                buildScoreProvider,
                dimension,
                M,
                EF_CONSTRUCTION,
                NEIGHBOR_OVERFLOW,
                ALPHA,
                ADD_HIERARCHY,
                REFINE_FINAL_GRAPH
        )) {
            newGraph = builder.build(newRandomAccessVectorValues);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        ordinalToId = List.copyOf(newOrdinalToId);
        randomAccessVectorValues = newRandomAccessVectorValues;
        graph = newGraph;
        dirty = false;
    }

    private void validateVector(float[] vector) {
        if (vector == null) {
            throw new IllegalArgumentException("vector is required");
        }

        if (vector.length != dimension) {
            throw new IllegalArgumentException(
                    "incorrect vector dimension: " + vector.length
                            + ", required: " + dimension
            );
        }
    }

    private double euclideanDistance(float[] a, float[] b) {
        double sum = 0.0;

        for (int i = 0; i < a.length; i++) {
            double diff = a[i] - b[i];
            sum += diff * diff;
        }

        return Math.sqrt(sum);
    }
}