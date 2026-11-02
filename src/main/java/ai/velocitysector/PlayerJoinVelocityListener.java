package ai.velocitysector;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.EventTask;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.player.PlayerChooseInitialServerEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import org.slf4j.Logger;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** Restores a player's preferred backend without blocking Velocity's event thread. */
public final class PlayerJoinVelocityListener {
    private final java.util.function.BiFunction<UUID, String, java.util.concurrent.CompletableFuture<String>> routeLoader;
    private final ProxyServer proxy;
    private final Logger logger;
    private final ConnectionGeneration connectionGeneration = new ConnectionGeneration();
    private volatile boolean closing;

    public PlayerJoinVelocityListener(VelocityPlugin plugin, PlayerSectorPersistence playerSectorPersistence,
                                      ProxyServer proxy, Logger logger) {
        this(playerSectorPersistence::loadAndUpdate, proxy, logger);
    }

    PlayerJoinVelocityListener(java.util.function.BiFunction<UUID, String, java.util.concurrent.CompletableFuture<String>> routeLoader,
                               ProxyServer proxy, Logger logger) {
        this.routeLoader = routeLoader;
        this.proxy = proxy;
        this.logger = logger;
    }

    @Subscribe
    public EventTask onChooseInitialServer(PlayerChooseInitialServerEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        UUID generation = connectionGeneration.begin(uuid, player);
        String playerName = player.getUsername();
        // Resolve before any Paper join. A provisional backend must not consume pending backup/transfer keys
        // and then lose its inventory when a late preferred-sector read connects directly to another backend.
        return EventTask.withContinuation(continuation -> {
            java.util.concurrent.CompletableFuture<String> load;
            try { load = routeLoader.apply(uuid, playerName); }
            catch (RuntimeException error) { load = java.util.concurrent.CompletableFuture.failedFuture(error); }
            load.orTimeout(4, TimeUnit.SECONDS)
                .whenComplete((targetSector, error) -> {
                    try {
                        if (closing || !connectionGeneration.isCurrent(uuid, generation) || !player.isActive()) return;
                        if (error != null) {
                            logger.warn("Could not resolve initial preferred sector for {}; using configured fallback", playerName, error);
                            return;
                        }
                        proxy.getServer(targetSector).ifPresentOrElse(event::setInitialServer,
                                () -> logger.warn("Unregistered preferred sector {} for {}; using configured fallback", targetSector, playerName));
                    } finally {
                        continuation.resume();
                    }
                });
        });
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        connectionGeneration.invalidate(event.getPlayer().getUniqueId(), event.getPlayer());
    }

    @Subscribe
    public void onShutdown(ProxyShutdownEvent event) {
        closing = true;
    }
}
