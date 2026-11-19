package ai.velocitysector;

import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class SpawnExitGateTest {
    @Test void deniesOrdinarySwitchAndOnlyConsumesMatchingSessionAndTargetOnce() {
        var gate = new SpawnExitGate("Spawn");
        var spawn = server("Spawn");
        var west = server("GameWest");
        var east = server("GameEast");
        UUID uuid = UUID.randomUUID();
        var player = player(uuid, spawn);
        var otherSession = player(uuid, spawn);

        assertFalse(allowed(gate, player, spawn, west));
        gate.authorize(player, west);
        assertFalse(allowed(gate, player, spawn, east));
        assertFalse(allowed(gate, otherSession, spawn, west));
        assertTrue(allowed(gate, player, spawn, west));
        assertFalse(allowed(gate, player, spawn, west));
        assertTrue(allowed(gate, player(uuid, east), east, west));
        assertTrue(allowed(gate, player(uuid, null), null, spawn));
    }

    private static boolean allowed(SpawnExitGate gate, Player player, RegisteredServer from, RegisteredServer to) {
        var event = new ServerPreConnectEvent(player, to, from);
        gate.onConnect(event);
        return event.getResult().isAllowed();
    }

    private static RegisteredServer server(String name) {
        return proxy(RegisteredServer.class, (p, method, args) ->
                new ServerInfo(name, new InetSocketAddress("127.0.0.1", 25565)));
    }

    private static Player player(UUID uuid, RegisteredServer current) {
        var connection = current == null ? null : proxy(ServerConnection.class, (p, method, args) -> current.getServerInfo());
        return proxy(Player.class, (p, method, args) -> switch (method.getName()) {
            case "getUniqueId" -> uuid;
            case "getCurrentServer" -> Optional.ofNullable(connection);
            default -> throw new AssertionError(method.getName());
        });
    }

    private static <T> T proxy(Class<T> type, java.lang.reflect.InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, handler));
    }
}
