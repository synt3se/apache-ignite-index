package ru.nsu.fit.sberlab.vectorindex.node.index;

import static java.time.Duration.ofSeconds;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import javax.cache.Cache;

import org.apache.ignite.Ignite;
import org.apache.ignite.IgniteCache;
import org.apache.ignite.IgniteCluster;
import org.apache.ignite.IgniteLogger;
import org.apache.ignite.cache.CachePeekMode;
import org.apache.ignite.cache.affinity.Affinity;
import org.apache.ignite.cache.query.QueryCursor;
import org.apache.ignite.cache.query.ScanQuery;
import org.apache.ignite.cluster.ClusterNode;
import org.apache.ignite.events.CacheEvent;
import org.apache.ignite.events.Event;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import ru.nsu.fit.sberlab.vectorindex.common.ScoredVector;
import ru.nsu.fit.sberlab.vectorindex.common.VectorObject;
import ru.nsu.fit.sberlab.vectorindex.common.indextype.IndexType;

/** Cache and affinity are mocked; indexing, event application and searching are real. */
@Timeout(15)
class PartitionIndexManagerTest {

    @Test
    void scansOwnedPartitionsAndMergesTheirTopKByDistance() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.owned.set(new int[]{0, 1});
            fixture.put(10, 1, 0);
            fixture.put(12, -1, 0);
            fixture.put(31, 0, 1);
            fixture.cacheValues.put(14L, new VectorObject(null, "no-vector", ""));

            fixture.rebuildAll();

            assertThat(fixture.manager.activePartitionsCount()).isEqualTo(2);
            assertThat(fixture.manager.searchLocal(new float[]{1, 0}, 2))
                    .extracting(ScoredVector::id, ScoredVector::distance)
                    .containsExactly(tuple(10L, 0.0), tuple(31L, 1.0));
            assertThat(fixture.manager.localStats().liveVectors).isEqualTo(3);
        }
    }

    @Test
    void queuedEventReadsCurrentCacheValueInsteadOfTheValueAtEventTime() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.put(10, 1, 0);
            fixture.rebuildAll();
            fixture.blockApplier();
            fixture.put(10, -1, 0);
            fixture.event(10);
            fixture.put(10, 0, 1);

            fixture.releaseApplier();

            fixture.awaitSearch(new float[]{0, 1}, new ScoredVector(10, 0));
        }
    }

    @Test
    void repeatedUpdatesAndRemovalsConvergeWithoutDuplicateOrStaleVectors() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.put(10, 1, 0);
            fixture.rebuildAll();

            fixture.put(10, 0, 1);
            fixture.eventAndAwaitApplied(10, 1);
            fixture.eventAndAwaitApplied(10, 2);
            assertThat(fixture.manager.searchLocal(new float[]{0, 1}, 10))
                    .extracting(ScoredVector::id, ScoredVector::distance)
                    .containsExactly(tuple(10L, 0.0));

            fixture.cacheValues.remove(10L);
            fixture.eventAndAwaitApplied(10, 3);
            fixture.eventAndAwaitApplied(10, 4);
            assertThat(fixture.manager.searchLocal(new float[]{0, 1}, 10)).isEmpty();

            fixture.put(10, 1, 0);
            fixture.eventAndAwaitApplied(10, 5);
            fixture.cacheValues.put(10L, new VectorObject(null, "no-vector", ""));
            fixture.eventAndAwaitApplied(10, 6);
            assertThat(fixture.manager.searchLocal(new float[]{1, 0}, 10)).isEmpty();
        }
    }

    @Test
    void localPeekMissFallsBackToCacheGet() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.rebuildAll();
            fixture.put(10, 0, 1);
            when(fixture.cache.localPeek(10L, CachePeekMode.PRIMARY, CachePeekMode.BACKUP))
                    .thenReturn(null);

            fixture.eventAndAwaitApplied(10, 1);

            assertThat(fixture.manager.searchLocal(new float[]{0, 1}, 10))
                    .extracting(ScoredVector::id, ScoredVector::distance)
                    .containsExactly(tuple(10L, 0.0));
        }
    }

    @Test
    void reconcileRemovesSearchResultsForPartitionsThatMovedToAnotherNode() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.owned.set(new int[]{0, 1});
            fixture.put(10, 1, 0);
            fixture.put(31, 0, 1);
            fixture.rebuildAll();

            fixture.owned.set(new int[]{1});
            fixture.manager.forceReconcile(false);
            fixture.rebuilds.runAll();

            assertThat(fixture.manager.activePartitionsCount()).isEqualTo(1);
            assertThat(fixture.manager.searchLocal(new float[]{1, 0}, 10))
                    .extracting(ScoredVector::id, ScoredVector::distance)
                    .containsExactly(tuple(31L, 1.0));
        }
    }

    @Test
    void overflowWhileIdleIsRepairedByReconcileEvenWithoutAnotherCacheEvent() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.put(10, 1, 0);
            fixture.rebuildAll();
            fixture.saturateApplier();
            fixture.put(10, 0, 1);
            fixture.event(10); // This event cannot enter the full queue.

            fixture.releaseApplier();
            fixture.manager.forceReconcile(false);
            fixture.rebuilds.runAll();

            assertThat(fixture.manager.searchLocal(new float[]{0, 1}, 10))
                    .extracting(ScoredVector::id, ScoredVector::distance)
                    .containsExactly(tuple(10L, 0.0));
        }
    }

    @Test
    void acceptedChangesDuringScanAreReplayedBeforeTheNewIndexIsPublished() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.put(10, 1, 0);
            fixture.put(20, 0, 1);
            fixture.rebuildAll();
            ScanBarrier scan = fixture.blockNextScanAfterItsLastEntry();
            fixture.manager.forceReconcile(true);
            Future<?> inFlight = fixture.rebuildWorker.submit(fixture.rebuilds::runNext);
            assertThat(scan.finishedReading.await(3, TimeUnit.SECONDS)).isTrue();

            fixture.put(10, 0, -1);
            fixture.event(10);
            fixture.flushApplier();
            fixture.cacheValues.remove(20L);
            fixture.event(20);
            fixture.flushApplier();
            fixture.put(30, 1, 0);
            fixture.event(30);
            fixture.flushApplier();

            scan.release.countDown();
            inFlight.get(3, TimeUnit.SECONDS);

            assertThat(fixture.manager.searchLocal(new float[]{0, -1}, 10))
                    .extracting(ScoredVector::id, ScoredVector::distance)
                    .containsExactly(tuple(10L, 0.0), tuple(30L, 1.0));
        }
    }

    @Test
    @Tag("known-issue")
    @DisplayName("К2: overflow during a running scan must survive reconcile and trigger recovery")
    void overflowDuringRebuildIsNotForgottenByConcurrentReconcile() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.put(10, 1, 0);
            fixture.rebuildAll();
            ScanBarrier scan = fixture.blockNextScanAfterItsLastEntry();
            fixture.manager.forceReconcile(true);
            Future<?> inFlight = fixture.rebuildWorker.submit(fixture.rebuilds::runNext);
            assertThat(scan.finishedReading.await(3, TimeUnit.SECONDS)).isTrue();
            fixture.saturateApplier();
            fixture.put(10, 0, 1);
            fixture.event(10); // Lost after this key has already been scanned.
            fixture.manager.forceReconcile(false); // The original rebuild is still queued/running.

            scan.release.countDown();
            inFlight.get(3, TimeUnit.SECONDS);
            fixture.releaseApplier();
            fixture.manager.forceReconcile(false);
            fixture.rebuilds.runAll();

            // No new event or forced full rebuild: periodic reconcile must suffice to converge.
            assertThat(fixture.manager.searchLocal(new float[]{0, 1}, 10))
                    .extracting(ScoredVector::id, ScoredVector::distance)
                    .containsExactly(tuple(10L, 0.0));
        }
    }

    @Test
    @DisplayName("К5: a managed bulk pause finishes with a full rebuild on resume")
    void resumeAfterBulkClearIndexesWritesWhoseEventsWerePaused() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.put(10, 1, 0);
            fixture.rebuildAll();
            fixture.manager.pauseIndexing(60_000);
            fixture.manager.clearAll();
            fixture.cacheValues.clear();
            fixture.put(20, 0, 1);
            fixture.event(20);
            fixture.manager.forceReconcile(false);
            fixture.rebuilds.runAll();
            assertThat(fixture.manager.searchLocal(new float[]{0, 1}, 10)).isEmpty();

            fixture.manager.resumeIndexing();
            fixture.rebuilds.runAll();

            assertThat(fixture.manager.searchLocal(new float[]{0, 1}, 10))
                    .extracting(ScoredVector::id, ScoredVector::distance)
                    .containsExactly(tuple(20L, 0.0));
        }
    }

    private static final class Fixture implements AutoCloseable {
        final Map<Long, VectorObject> cacheValues = new ConcurrentHashMap<>();
        final AtomicReference<int[]> owned = new AtomicReference<>(new int[]{0});
        final ManualExecutor rebuilds = new ManualExecutor();
        final ExecutorService rebuildWorker = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "manager-test-scan");
            thread.setDaemon(true);
            return thread;
        });
        final StripedApplier applier = new StripedApplier(1, 1);
        final IgniteCache<Long, VectorObject> cache;
        final PartitionIndexManager manager;
        final AtomicReference<ScanBarrier> nextScan = new AtomicReference<>();
        final List<ScanBarrier> scanBarriers = new ArrayList<>();
        private final CountDownLatch applierRelease = new CountDownLatch(1);

        @SuppressWarnings("unchecked")
        Fixture() throws Exception {
            Ignite ignite = mock(Ignite.class);
            IgniteCluster cluster = mock(IgniteCluster.class);
            ClusterNode local = mock(ClusterNode.class);
            Affinity<Long> affinity = mock(Affinity.class);
            cache = mock(IgniteCache.class);
            when(ignite.cluster()).thenReturn(cluster);
            when(cluster.localNode()).thenReturn(local);
            when(local.consistentId()).thenReturn("unit-test-node");
            when(local.isLocal()).thenReturn(true);
            when(affinity.primaryPartitions(local)).thenAnswer(invocation -> owned.get());
            when(affinity.mapPartitionToNode(anyInt())).thenReturn(local);
            when(affinity.partition(anyLong())).thenAnswer(invocation -> partition(invocation.getArgument(0)));
            when(cache.localPeek(anyLong(), any(CachePeekMode[].class)))
                    .thenAnswer(invocation -> cacheValues.get(invocation.getArgument(0)));
            when(cache.get(anyLong())).thenAnswer(invocation -> cacheValues.get(invocation.getArgument(0)));
            when(cache.query(any(ScanQuery.class))).thenAnswer(invocation -> {
                ScanQuery<Long, VectorObject> query = invocation.getArgument(0);
                List<Cache.Entry<Long, VectorObject>> entries = new ArrayList<>();
                cacheValues.forEach((key, value) -> {
                    if (partition(key) == query.getPartition()) {
                        Cache.Entry<Long, VectorObject> entry = mock(Cache.Entry.class);
                        when(entry.getKey()).thenReturn(key);
                        when(entry.getValue()).thenReturn(value);
                        entries.add(entry);
                    }
                });
                Iterator<Cache.Entry<Long, VectorObject>> snapshot = entries.iterator();
                ScanBarrier barrier = nextScan.getAndSet(null);
                QueryCursor<Cache.Entry<Long, VectorObject>> cursor = mock(QueryCursor.class);
                when(cursor.iterator()).thenReturn(new Iterator<>() {
                    private boolean signaled;

                    @Override
                    public boolean hasNext() {
                        boolean available = snapshot.hasNext();
                        if (!available && barrier != null && !signaled) {
                            signaled = true;
                            barrier.finishedReading.countDown();
                            awaitLatch(barrier.release);
                        }
                        return available;
                    }

                    @Override
                    public Cache.Entry<Long, VectorObject> next() {
                        return snapshot.next();
                    }
                });
                return cursor;
            });

            manager = new PartitionIndexManager(ignite, "vectors", 2,
                    IndexType.BRUTE_FORCE_INDEX, new JVectorProperties());
            // There is no executor/listener injection seam. Avoid start(), real clusters and timers.
            setField(manager, "affinity", affinity);
            setField(manager, "cache", cache);
            setField(manager, "applier", applier);
            setField(manager, "rebuildPool", rebuilds);
            setField(manager, "housekeeper", mock(ScheduledExecutorService.class));
            setField(manager, "log", mock(IgniteLogger.class));
        }

        void put(long key, float x, float y) {
            cacheValues.put(key, new VectorObject(new float[]{x, y}, "vector-" + key, ""));
        }

        void rebuildAll() {
            manager.forceReconcile(true);
            rebuilds.runAll();
        }

        void event(long key) throws Exception {
            CacheEvent event = mock(CacheEvent.class);
            when(event.cacheName()).thenReturn("vectors");
            when(event.key()).thenReturn(key);
            Method listener = PartitionIndexManager.class.getDeclaredMethod("onCacheEvent", Event.class);
            listener.setAccessible(true);
            try {
                listener.invoke(manager, event);
            } catch (InvocationTargetException failure) {
                throw new AssertionError("Cache event listener failed", failure.getCause());
            }
        }

        void eventAndAwaitApplied(long key, long expectedTotal) throws Exception {
            event(key);
            await().atMost(ofSeconds(3)).untilAsserted(() ->
                    assertThat(manager.localStats().appliedTotal).isEqualTo(expectedTotal));
        }

        void awaitSearch(float[] query, ScoredVector expected) {
            await().atMost(ofSeconds(3)).untilAsserted(() ->
                    assertThat(manager.searchLocal(query, 10))
                            .extracting(ScoredVector::id, ScoredVector::distance)
                            .containsExactly(tuple(expected.id(), expected.distance())));
        }

        void blockApplier() throws InterruptedException {
            CountDownLatch entered = new CountDownLatch(1);
            assertThat(applier.submit(0, () -> {
                entered.countDown();
                awaitLatch(applierRelease);
            })).isTrue();
            assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
        }

        void saturateApplier() throws InterruptedException {
            blockApplier();
            assertThat(applier.submit(0, () -> {})).isTrue();
        }

        void releaseApplier() {
            applierRelease.countDown();
        }

        void flushApplier() throws InterruptedException {
            CountDownLatch finished = new CountDownLatch(1);
            await().atMost(ofSeconds(3)).until(() -> applier.submit(0, finished::countDown));
            assertThat(finished.await(3, TimeUnit.SECONDS)).isTrue();
        }

        ScanBarrier blockNextScanAfterItsLastEntry() {
            ScanBarrier barrier = new ScanBarrier();
            scanBarriers.add(barrier);
            nextScan.set(barrier);
            return barrier;
        }

        @Override
        public void close() throws InterruptedException {
            applierRelease.countDown();
            scanBarriers.forEach(barrier -> barrier.release.countDown());
            rebuildWorker.shutdownNow();
            manager.stop();
            assertThat(rebuildWorker.awaitTermination(3, TimeUnit.SECONDS)).isTrue();
        }

        private static int partition(long key) {
            return Math.floorMod(key, 2);
        }
    }

    private static final class ScanBarrier {
        final CountDownLatch finishedReading = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
    }

    private static final class ManualExecutor extends AbstractExecutorService {
        private final Queue<Runnable> tasks = new ArrayDeque<>();
        private boolean shutdown;

        void runNext() {
            Runnable task;
            synchronized (tasks) {
                task = tasks.remove();
            }
            task.run();
        }

        void runAll() {
            // Bound the drain so a broken automatic retry cannot hang a test.
            for (int i = 0; i < 20; i++) {
                synchronized (tasks) {
                    if (tasks.isEmpty()) return;
                }
                runNext();
            }
            throw new AssertionError("Rebuild did not settle after 20 passes");
        }

        @Override
        public void execute(Runnable command) {
            synchronized (tasks) {
                if (shutdown) throw new RejectedExecutionException("Executor is closed");
                tasks.add(command);
            }
        }

        @Override
        public void shutdown() {
            synchronized (tasks) {
                shutdown = true;
            }
        }

        @Override
        public List<Runnable> shutdownNow() {
            synchronized (tasks) {
                shutdown = true;
                List<Runnable> remaining = new ArrayList<>(tasks);
                tasks.clear();
                return remaining;
            }
        }

        @Override
        public boolean isShutdown() {
            synchronized (tasks) {
                return shutdown;
            }
        }

        @Override
        public boolean isTerminated() {
            synchronized (tasks) {
                return shutdown && tasks.isEmpty();
            }
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return isTerminated();
        }
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = PartitionIndexManager.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static void awaitLatch(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("Timed out waiting for the test barrier");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted at test barrier", interrupted);
        }
    }
}
