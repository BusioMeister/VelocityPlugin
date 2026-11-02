package ai.velocitysector;

import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class OnlinePlayersListenerTest {
    @Test
    void keepsValidSnapshotUntilItsLeaseExpires() {
        AtomicLong clock = new AtomicLong(1_000L);
        OnlinePlayersListener listener = new OnlinePlayersListener(100L, clock::get);
        listener.onPMessage("sector-online:*", "sector-online:Sector1", "{\"uuid\":\"Alice\"}");

        assertEquals(Set.of("Alice"), listener.getOnlinePlayersInSector("Sector1"));
        assertEquals("Sector1", listener.getPlayerSector("alice"));
        assertEquals(Set.of("Alice"), listener.getAllOnlinePlayers());

        clock.set(1_099L);
        assertEquals("Sector1", listener.getPlayerSector("Alice"));

        clock.set(1_100L);
        assertEquals(Set.of(), listener.getAllOnlinePlayers());
        assertNull(listener.getPlayerSector("Alice"));
    }

    @Test
    void malformedMessageDoesNotReplaceLastGoodSnapshot() {
        AtomicLong clock = new AtomicLong(10L);
        OnlinePlayersListener listener = new OnlinePlayersListener(100L, clock::get);
        listener.onPMessage("sector-online:*", "sector-online:Sector2", "{\"uuid\":\"Bob\"}");
        clock.set(20L);

        listener.onPMessage("sector-online:*", "sector-online:Sector2", "not-json");

        assertEquals(Set.of("Bob"), listener.getOnlinePlayersInSector("Sector2"));
    }

    @Test
    void rejectsOversizedOrInvalidSnapshotWithoutReplacingLastGoodData() {
        OnlinePlayersListener listener = new OnlinePlayersListener(100L, () -> 20L);
        listener.onPMessage("sector-online:*", "sector-online:Sector3", "{\"uuid\":\"Alice\"}");

        listener.onPMessage("sector-online:*", "sector-online:Sector3", "{\"uuid\":\"a-name-longer-than-16\"}");
        listener.onPMessage("sector-online:*", "sector-online:" + "S".repeat(129), "{}");
        listener.onPMessage("sector-online:*", "sector-online:Sector3", "x".repeat(65_537));

        assertEquals(Set.of("Alice"), listener.getOnlinePlayersInSector("Sector3"));
    }

    @Test
    void ambiguousNameAcrossSectorsDoesNotProduceArbitraryRoute() {
        OnlinePlayersListener listener = new OnlinePlayersListener(100L, () -> 20L);
        listener.onPMessage("sector-online:*", "sector-online:Sector1", "{\"one\":\"Alice\"}");
        listener.onPMessage("sector-online:*", "sector-online:Sector2", "{\"two\":\"alice\"}");

        assertNull(listener.getPlayerSector("Alice"));
    }

    @Test
    void rejectsNamesOutsideMinecraftUsernameAlphabet() {
        OnlinePlayersListener listener = new OnlinePlayersListener(100L, () -> 20L);
        listener.onPMessage("sector-online:*", "sector-online:Sector1", "{\"one\":\"Alice!\"}");

        assertEquals(Set.of(), listener.getOnlinePlayersInSector("Sector1"));
    }
}
