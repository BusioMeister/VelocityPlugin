package ai.velocitysector;

import com.velocitypowered.api.event.Continuation;
import com.velocitypowered.api.event.player.PlayerChooseInitialServerEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import java.lang.reflect.Proxy;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class InitialServerRoutingTest {
    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, java.lang.reflect.InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler);
    }

    @Test void resolvesPreferredSectorBeforeAnyBackendJoinAndResumesExactlyOnce() {
        UUID uuid = UUID.randomUUID();
        RegisteredServer fallback = proxy(RegisteredServer.class, (p, m, a) -> null);
        RegisteredServer preferred = proxy(RegisteredServer.class, (p, m, a) -> null);
        Player player = proxy(Player.class, (p, m, a) -> switch (m.getName()) {
            case "getUniqueId" -> uuid;
            case "getUsername" -> "Fixture";
            case "isActive" -> true;
            default -> throw new AssertionError("No provisional connection is allowed: " + m.getName());
        });
        ProxyServer server = proxy(ProxyServer.class, (p, m, a) -> {
            assertEquals("getServer", m.getName());
            return Optional.of(preferred);
        });
        CompletableFuture<String> gate = new CompletableFuture<>();
        var listener = new PlayerJoinVelocityListener((id, name) -> gate, server, LoggerFactory.getLogger("test"));
        var event = new PlayerChooseInitialServerEvent(player, fallback);
        AtomicInteger resumed = new AtomicInteger();
        listener.onChooseInitialServer(event).execute(new Continuation() {
            public void resume() { resumed.incrementAndGet(); }
            public void resumeWithException(Throwable error) { fail(error); }
        });
        assertEquals(0, resumed.get());
        assertSame(fallback, event.getInitialServer().orElseThrow());
        gate.complete("Sector3");
        assertSame(preferred, event.getInitialServer().orElseThrow());
        assertEquals(1, resumed.get());
    }

    @Test void disconnectedPlayerCannotApplyALatePreferredSector() {
        AtomicBoolean active = new AtomicBoolean(true);
        UUID uuid = UUID.randomUUID();
        Player player = proxy(Player.class, (p, m, a) -> switch (m.getName()) {
            case "getUniqueId" -> uuid;
            case "getUsername" -> "Fixture";
            case "isActive" -> active.get();
            default -> null;
        });
        ProxyServer server = proxy(ProxyServer.class, (p, m, a) -> { throw new AssertionError("Late lookup"); });
        CompletableFuture<String> gate = new CompletableFuture<>();
        var listener = new PlayerJoinVelocityListener((id, name) -> gate, server, LoggerFactory.getLogger("test"));
        var event = new PlayerChooseInitialServerEvent(player, null);
        AtomicInteger resumed = new AtomicInteger();
        listener.onChooseInitialServer(event).execute(new Continuation() {
            public void resume() { resumed.incrementAndGet(); }
            public void resumeWithException(Throwable error) { fail(error); }
        });
        active.set(false);
        gate.complete("Sector3");
        assertTrue(event.getInitialServer().isEmpty());
        assertEquals(1, resumed.get());
    }
}
