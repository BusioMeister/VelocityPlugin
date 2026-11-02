package ai.velocitysector;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ServerConnection;
import org.slf4j.Logger;

import java.util.Optional;
import java.util.Map;
import java.util.UUID;

public final class PlayerDisconnectListener {
    private final PlayerSectorPersistence playerSectorPersistence;
    private final Logger logger;
    private final Map<UUID, PendingTpaRequest> tpaRequests;
    private final NetworkListener networkListener;

    public PlayerDisconnectListener(PlayerSectorPersistence playerSectorPersistence, Logger logger,
                                    Map<UUID, PendingTpaRequest> tpaRequests,
                                    NetworkListener networkListener) {
        this.playerSectorPersistence = playerSectorPersistence;
        this.logger = logger;
        this.tpaRequests = tpaRequests;
        this.networkListener = networkListener;
    }

    @Subscribe
    public void onPlayerDisconnect(DisconnectEvent event) {
        Player player = event.getPlayer();
        UUID playerUuid = player.getUniqueId();
        networkListener.cancelPendingTransfer(playerUuid);
        tpaRequests.remove(playerUuid);
        tpaRequests.forEach((targetUuid, request) -> {
            if (request.requesterUuid().equals(playerUuid)) {
                tpaRequests.remove(targetUuid, request);
            }
        });

        Optional<ServerConnection> currentServer = player.getCurrentServer();
        if (currentServer.isEmpty()) {
            return;
        }

        String playerName = player.getUsername();
        String lastSector = currentServer.get().getServerInfo().getName();

        playerSectorPersistence.saveLastSector(playerUuid, playerName, lastSector)
                .exceptionally(error -> {
                    logger.error("Could not save last sector {} for player {}", lastSector, playerName, error);
                    return null;
                });
    }
}
