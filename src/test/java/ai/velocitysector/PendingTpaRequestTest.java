package ai.velocitysector;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PendingTpaRequestTest {
    @Test
    void expiresAtDeadlineWithoutExpiringEarlier() {
        PendingTpaRequest request = new PendingTpaRequest(UUID.randomUUID(), UUID.randomUUID(), 1_500L, false);

        assertFalse(request.isExpired(1_499L));
        assertTrue(request.isExpired(1_500L));
    }

    @Test
    void claimingPreservesRequestIdentityAndDeadline() {
        UUID requestId = UUID.randomUUID();
        UUID requester = UUID.randomUUID();
        PendingTpaRequest pending = new PendingTpaRequest(requestId, requester, 1_500L, false);

        PendingTpaRequest claimed = pending.claim();

        assertTrue(claimed.claimed());
        assertFalse(pending.claimed());
        assertTrue(claimed.requestId().equals(pending.requestId()));
        assertTrue(claimed.requesterUuid().equals(pending.requesterUuid()));
        assertTrue(claimed.expiresAtNanos() == pending.expiresAtNanos());
    }
}
