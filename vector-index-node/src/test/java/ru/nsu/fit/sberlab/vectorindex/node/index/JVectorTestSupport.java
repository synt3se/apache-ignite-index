package ru.nsu.fit.sberlab.vectorindex.node.index;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Test-only scheduling seam: m() is read after the background task captures its source data. */
final class JVectorTestSupport {
    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    private JVectorTestSupport() { }

    static JVectorProperties properties() {
        JVectorProperties properties = new JVectorProperties();
        configure(properties);
        return properties;
    }

    private static void configure(JVectorProperties properties) {
        properties.setM(8);
        properties.setEfConstruction(32);
        properties.setEfSearch(8);
        properties.setNeighborOverflow(1.2f);
        properties.setAlpha(1.2f);
        properties.setAddHierarchy(false);
        properties.setRefineFinalGraph(true);
        properties.setMinAdditionsBeforeRebuild(10_000);
        properties.setAddRebuildRatio(1);
        properties.setDeleteRebuildRatio(10);
    }

    static void awaitRebuildFinished(JVectorPartitionIndex index) throws ReflectiveOperationException {
        // No public completion handle exists. Read the production flag under its own lock,
        // rather than infer publication from ANN recall or wait an arbitrary amount of time.
        Field lockField = JVectorPartitionIndex.class.getDeclaredField("lock");
        Field rebuildingField = JVectorPartitionIndex.class.getDeclaredField("rebuildInProgress");
        lockField.setAccessible(true);
        rebuildingField.setAccessible(true);
        ReentrantReadWriteLock lock = (ReentrantReadWriteLock) lockField.get(index);
        await().alias("JVector background rebuild to finish")
                .atMost(TIMEOUT).pollInterval(Duration.ofMillis(5)).until(() -> {
                    lock.readLock().lock();
                    try {
                        return !rebuildingField.getBoolean(index);
                    } finally {
                        lock.readLock().unlock();
                    }
                });
    }

    static final class PausingProperties extends JVectorProperties {
        private final CountDownLatch captured = new CountDownLatch(1);
        private final CountDownLatch resume = new CountDownLatch(1);
        private final AtomicBoolean armed = new AtomicBoolean();

        PausingProperties() {
            configure(this);
            setMinAdditionsBeforeRebuild(1);
            setAddRebuildRatio(0);
        }

        void pauseNextBuild() { armed.set(true); }

        void awaitCapture() throws InterruptedException {
            assertTrue(captured.await(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS),
                    "background build did not reach the point after source capture");
        }

        void release() { resume.countDown(); }

        @Override public int m() {
            if (armed.compareAndSet(true, false)) {
                captured.countDown();
                try {
                    if (!resume.await(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                        throw new IllegalStateException("test did not release the background build");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("background build interrupted", e);
                }
            }
            return super.m();
        }
    }
}
