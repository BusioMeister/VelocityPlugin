package ai.velocitysector;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/** Runs asynchronous operations for one key in submission order, without serializing unrelated keys. */
final class AsyncKeyedSequencer<K> {
    private final Map<K, CompletableFuture<Void>> tails = new HashMap<>();
    private int pending;

    <T> CompletableFuture<T> submit(K key, Supplier<CompletableFuture<T>> operation) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(operation, "operation");

        CompletableFuture<Void> previous;
        CompletableFuture<Void> tail = new CompletableFuture<>();
        synchronized (this) {
            if (pending >= 4096) return CompletableFuture.failedFuture(
                    new java.util.concurrent.RejectedExecutionException("Async sequencer is full"));
            pending++;
            previous = tails.put(key, tail);
        }
        CompletableFuture<T> result = (previous == null
                ? CompletableFuture.completedFuture(null)
                : previous.handle((ignored, error) -> null))
                .thenCompose(ignored -> {
                    try {
                        return Objects.requireNonNull(operation.get(), "operation future");
                    } catch (RuntimeException exception) {
                        return CompletableFuture.failedFuture(exception);
                    }
                });
        result.whenComplete((ignored, error) -> {
            synchronized (this) {
                tails.remove(key, tail);
                pending--;
            }
            tail.complete(null);
        });
        return result.copy();
    }
}
