package ru.nsu.fit.sberlab.vectorindex.node.search;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Ожидание ответа не дольше остатка бюджета; внедряется, чтобы тесты управляли временем. */
public interface Awaiter {

    Awaiter BLOCKING = new Awaiter() {
        @Override
        public <T> T await(CompletableFuture<T> future, long timeoutNanos)
                throws InterruptedException, ExecutionException, TimeoutException {
            return future.get(timeoutNanos, TimeUnit.NANOSECONDS);
        }
    };

    <T> T await(CompletableFuture<T> future, long timeoutNanos)
            throws InterruptedException, ExecutionException, TimeoutException;
}
