package ru.nsu.fit.sberlab.vectorindex.testkit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ForkJoinPool;

import ru.nsu.fit.sberlab.vectorindex.core.BuildToken;
import ru.nsu.fit.sberlab.vectorindex.core.Hit;
import ru.nsu.fit.sberlab.vectorindex.core.LocalSearchResult;
import ru.nsu.fit.sberlab.vectorindex.core.PartitionRuntime;
import ru.nsu.fit.sberlab.vectorindex.core.PublishResult;
import ru.nsu.fit.sberlab.vectorindex.core.ReadView;
import ru.nsu.fit.sberlab.vectorindex.core.RuntimeConfig;
import ru.nsu.fit.sberlab.vectorindex.core.RuntimeStatus;
import ru.nsu.fit.sberlab.vectorindex.core.ScanSession;
import ru.nsu.fit.sberlab.vectorindex.core.SearchParams;
import ru.nsu.fit.sberlab.vectorindex.core.VectorSpec;
import ru.nsu.fit.sberlab.vectorindex.core.algorithm.BuildContext;
import ru.nsu.fit.sberlab.vectorindex.core.algorithm.SearchStructure;
import ru.nsu.fit.sberlab.vectorindex.core.algorithm.VectorAlgorithm;

/** Явное управление runtime в тестах: скан, захват, сборка и публикация — по шагам. */
public final class RuntimeHarness implements AutoCloseable {

    public static final ForkJoinPool BUILD_POOL = new ForkJoinPool(2);

    public final PartitionRuntime<Long> runtime;
    public final TrackingAlgorithm algorithm;

    public RuntimeHarness(VectorSpec spec, VectorAlgorithm algorithm) {
        this.algorithm = new TrackingAlgorithm(algorithm);
        this.runtime = PartitionRuntime.create(RuntimeConfig.of("harness", 0, spec, this.algorithm));
    }

    /** Публикует результат скана с заданным содержимым источника. */
    public RuntimeHarness ready(Map<Long, float[]> source) {
        ScanSession<Long> scan = runtime.beginScan();
        source.forEach(scan::offer);
        assertThat(runtime.publishScan(scan)).isEqualTo(PublishResult.PUBLISHED);
        return this;
    }

    public RuntimeHarness ready() {
        return ready(Map.of());
    }

    /** Скан + сборка: база построена алгоритмом, дельта пуста. */
    public RuntimeHarness built(Map<Long, float[]> source) throws Exception {
        ready(source);
        optimize();
        return this;
    }

    public BuildToken<Long> capture() {
        return runtime.capture().orElseThrow(() -> new AssertionError("nothing captured"));
    }

    public SearchStructure build(BuildToken<Long> token) throws Exception {
        return algorithm.build(token.input(), BuildContext.of(BUILD_POOL));
    }

    public PublishResult finish(BuildToken<Long> token) throws Exception {
        return runtime.publish(token, build(token));
    }

    public void optimize() throws Exception {
        assertThat(finish(capture())).isEqualTo(PublishResult.PUBLISHED);
    }

    public ReadView<Long> view() {
        return runtime.openView().orElseThrow(() -> new AssertionError("runtime is closed"));
    }

    public LocalSearchResult<Long> searchResult(float[] query, SearchParams params) {
        try (ReadView<Long> view = view()) {
            return view.search(query, params);
        }
    }

    public List<Hit<Long>> search(float[] query, int k) {
        return searchResult(query, SearchParams.topK(k)).hits();
    }

    public List<Hit<Long>> exact(float[] query, int k) {
        return searchResult(query, SearchParams.exactTopK(k)).hits();
    }

    public long size() {
        try (ReadView<Long> view = view()) {
            return view.size();
        }
    }

    public Map<Long, float[]> content() {
        Map<Long, float[]> out = new HashMap<>();
        try (ReadView<Long> view = view()) {
            view.forEachLive(out::put);
        }
        return out;
    }

    public RuntimeStatus status() {
        return runtime.status();
    }

    @Override
    public void close() {
        runtime.close();
    }
}
