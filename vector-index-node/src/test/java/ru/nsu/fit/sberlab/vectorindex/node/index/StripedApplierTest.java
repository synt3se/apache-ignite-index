package ru.nsu.fit.sberlab.vectorindex.node.index;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(10)
class StripedApplierTest {

    @Test
    void appliesAcceptedChangesInSubmissionOrderForTheSamePartition() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(3);
        List<Integer> order = new CopyOnWriteArrayList<>();

        try (StripedApplier applier = new StripedApplier(2, 3)) {
            try {
                assertThat(applier.submit(7, () -> {
                    entered.countDown();
                    await(release);
                })).isTrue();
                assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
                for (int i = 0; i < 3; i++) {
                    int value = i;
                    assertThat(applier.submit(7, () -> {
                        order.add(value);
                        finished.countDown();
                    })).isTrue();
                }
                release.countDown();
                assertThat(finished.await(3, TimeUnit.SECONDS)).isTrue();
                assertThat(order).containsExactly(0, 1, 2);
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void blockedPartitionDoesNotBlockAnotherStripe() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch otherStripeFinished = new CountDownLatch(1);

        try (StripedApplier applier = new StripedApplier(2, 1)) {
            try {
                assertThat(applier.submit(0, () -> {
                    entered.countDown();
                    await(release);
                })).isTrue();
                assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
                assertThat(applier.submit(1, otherStripeFinished::countDown)).isTrue();
                assertThat(otherStripeFinished.await(3, TimeUnit.SECONDS)).isTrue();
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void overflowRejectsOnlyTheUnacceptedTaskAndStripeRecoversAfterDrain() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch acceptedFinished = new CountDownLatch(1);
        CountDownLatch recoveryFinished = new CountDownLatch(1);
        List<Integer> applied = new CopyOnWriteArrayList<>();

        try (StripedApplier applier = new StripedApplier(1, 1)) {
            try {
                assertThat(applier.submit(0, () -> {
                    entered.countDown();
                    await(release);
                })).isTrue();
                assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
                assertThat(applier.submit(0, () -> {
                    applied.add(1);
                    acceptedFinished.countDown();
                })).isTrue();
                assertThat(applier.submit(0, () -> applied.add(2))).isFalse();
                assertThat(applier.backlog()).isEqualTo(1);

                release.countDown();
                assertThat(acceptedFinished.await(3, TimeUnit.SECONDS)).isTrue();
                assertThat(applier.submit(0, () -> {
                    applied.add(3);
                    recoveryFinished.countDown();
                })).isTrue();
                assertThat(recoveryFinished.await(3, TimeUnit.SECONDS)).isTrue();
                assertThat(applied).containsExactly(1, 3);
            } finally {
                release.countDown();
            }
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("Timed out waiting to release the applier");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
