package ai.velocitysector;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConnectionGenerationTest {
    @org.junit.jupiter.api.Test
    void oldDisconnectCannotInvalidateNewPlayersInitialRouting() {
        ConnectionGeneration registry = new ConnectionGeneration();
        java.util.UUID player = java.util.UUID.randomUUID();
        Object oldSession = new Object(), newSession = new Object();
        registry.begin(player, oldSession);
        java.util.UUID current = registry.begin(player, newSession);
        registry.invalidate(player, oldSession);
        org.junit.jupiter.api.Assertions.assertTrue(registry.isCurrent(player, current));
        registry.invalidate(player, newSession);
        org.junit.jupiter.api.Assertions.assertFalse(registry.isCurrent(player, current));
    }

    @Test
    void invalidatesCallbacksFromPriorSessions() {
        ConnectionGeneration generations = new ConnectionGeneration();
        UUID player = UUID.randomUUID();

        UUID firstSession = generations.begin(player);
        assertTrue(generations.isCurrent(player, firstSession));
        generations.invalidate(player);
        assertFalse(generations.isCurrent(player, firstSession));

        UUID secondSession = generations.begin(player);
        assertTrue(generations.isCurrent(player, secondSession));
        assertFalse(generations.isCurrent(player, firstSession));
    }

    @Test
    void generationsAreIsolatedPerPlayer() {
        ConnectionGeneration generations = new ConnectionGeneration();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        UUID firstSession = generations.begin(first);
        UUID secondSession = generations.begin(second);

        generations.invalidate(first);

        assertFalse(generations.isCurrent(first, firstSession));
        assertTrue(generations.isCurrent(second, secondSession));
    }
}
