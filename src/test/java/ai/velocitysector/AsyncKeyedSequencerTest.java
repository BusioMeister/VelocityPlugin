package ai.velocitysector;

import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AsyncKeyedSequencerTest {
    @org.junit.jupiter.api.Test
    void cancellationDoesNotReleaseSerializationBarrier() {
        AsyncKeyedSequencer<String> sequencer = new AsyncKeyedSequencer<>();
        java.util.concurrent.CompletableFuture<Void> gate = new java.util.concurrent.CompletableFuture<>();
        sequencer.submit("p", () -> gate).cancel(false);
        java.util.concurrent.atomic.AtomicBoolean started = new java.util.concurrent.atomic.AtomicBoolean();
        var next = sequencer.submit("p", () -> {
            started.set(true);
            return java.util.concurrent.CompletableFuture.completedFuture(null);
        });
        org.junit.jupiter.api.Assertions.assertFalse(started.get());
        gate.complete(null);
        org.junit.jupiter.api.Assertions.assertTrue(next.isDone());
        org.junit.jupiter.api.Assertions.assertTrue(started.get());
    }

    @Test
    void serializesSameKeyAndContinuesAfterFailure() {
        AsyncKeyedSequencer<UUID> sequencer = new AsyncKeyedSequencer<>();
        UUID player = UUID.randomUUID();
        CompletableFuture<String> firstOperation = new CompletableFuture<>();
        AtomicBoolean secondStarted = new AtomicBoolean();

        CompletableFuture<String> first = sequencer.submit(player, () -> firstOperation);
        CompletableFuture<String> second = sequencer.submit(player, () -> {
            secondStarted.set(true);
            return CompletableFuture.completedFuture("saved");
        });

        assertFalse(secondStarted.get());
        firstOperation.completeExceptionally(new IllegalStateException("database failure"));

        assertThrows(java.util.concurrent.CompletionException.class, first::join);
        assertTrue(secondStarted.get());
        assertEquals("saved", second.join());
    }

    @Test
    void doesNotSerializeDifferentKeys() {
        AsyncKeyedSequencer<UUID> sequencer = new AsyncKeyedSequencer<>();
        CompletableFuture<String> pending = new CompletableFuture<>();
        UUID firstPlayer = UUID.randomUUID();

        sequencer.submit(firstPlayer, () -> pending);
        CompletableFuture<String> other = sequencer.submit(UUID.randomUUID(),
                () -> CompletableFuture.completedFuture("independent"));

        assertEquals("independent", other.join());
        assertFalse(pending.isDone());
    }
}
