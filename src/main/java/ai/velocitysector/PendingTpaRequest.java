package ai.velocitysector;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

public record PendingTpaRequest(UUID requestId, UUID requesterUuid, long expiresAtNanos, boolean claimed) {
    public static PendingTpaRequest create(UUID requesterUuid) {
        return new PendingTpaRequest(UUID.randomUUID(), requesterUuid,
                System.nanoTime() + TimeUnit.SECONDS.toNanos(60), false);
    }

    public PendingTpaRequest claim() {
        return new PendingTpaRequest(requestId, requesterUuid, expiresAtNanos, true);
    }

    public boolean isExpired(long nowNanos) {
        return nowNanos - expiresAtNanos >= 0L;
    }
}
