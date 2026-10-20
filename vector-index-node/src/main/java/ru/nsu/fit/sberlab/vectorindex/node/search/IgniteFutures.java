package ru.nsu.fit.sberlab.vectorindex.node.search;

import java.util.concurrent.CompletableFuture;

import org.apache.ignite.lang.IgniteFuture;

/** IgniteFuture → CompletableFuture; отмена пробрасывается обратно в Ignite. */
public final class IgniteFutures {

    private IgniteFutures() {
    }

    public static <T> CompletableFuture<T> toCompletable(IgniteFuture<T> future) {
        CompletableFuture<T> result = new CompletableFuture<>();
        future.listen(done -> {
            try {
                result.complete(done.get());
            } catch (RuntimeException failure) {
                result.completeExceptionally(failure);
            }
        });
        result.whenComplete((value, failure) -> {
            if (result.isCancelled()) {
                future.cancel();
            }
        });
        return result;
    }
}
