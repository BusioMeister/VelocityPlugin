package ai.velocitysector;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/** Blocks proxy-side routes (including /server) which bypass the Paper spawn button. */
final class SpawnExitGate {
    record Permit(Player player, String target, long expiresAt) { }
    private final String spawn;
    private final Map<UUID, Permit> permits = new ConcurrentHashMap<>();

    SpawnExitGate(String spawn) { this.spawn = spawn; }

    boolean onSpawn(Player player) {
        return player.getCurrentServer().map(server -> isSpawn(server.getServerInfo().getName())).orElse(false);
    }

    boolean isSpawn(String server) { return !spawn.isEmpty() && spawn.equalsIgnoreCase(server); }

    Permit authorize(Player player, RegisteredServer target) {
        var permit = new Permit(player, target.getServerInfo().getName(), System.nanoTime() + TimeUnit.SECONDS.toNanos(10));
        permits.put(player.getUniqueId(), permit);
        return permit;
    }

    void revoke(Permit permit) { permits.remove(permit.player().getUniqueId(), permit); }

    @Subscribe(priority = Short.MIN_VALUE)
    public void onConnect(ServerPreConnectEvent event) {
        Player player = event.getPlayer();
        var previous = event.getPreviousServer();
        if ((previous == null || !isSpawn(previous.getServerInfo().getName())) && !onSpawn(player)) return;
        var target = event.getResult().getServer().orElse(null);
        if (target == null || isSpawn(target.getServerInfo().getName())) return;
        Permit permit = permits.get(player.getUniqueId());
        if (permit != null && permit.player() == player && permit.expiresAt() - System.nanoTime() > 0
                && permit.target().equalsIgnoreCase(target.getServerInfo().getName())
                && permits.remove(player.getUniqueId(), permit)) return;
        event.setResult(ServerPreConnectEvent.ServerResult.denied());
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        Player player = event.getPlayer();
        permits.computeIfPresent(player.getUniqueId(), (id, permit) -> permit.player() == player ? null : permit);
    }
}
