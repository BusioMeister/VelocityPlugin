package ai.velocitysector;

import com.velocitypowered.api.proxy.ProxyServer;
import redis.clients.jedis.JedisPubSub;

import java.util.UUID;

public class TransferListener extends JedisPubSub {
    private final ProxyServer proxy;

    public TransferListener(ProxyServer proxy) {
        this.proxy = proxy;
    }

    @Override
    public void onMessage(String channel, String message) {
        // Oczekiwany format wiadomości: "UUID:targetServer"
        String[] data = message.split(":");
        if (data.length != 2) return;

        UUID uuid = UUID.fromString(data[0]);
        String targetServer = data[1];

        proxy.getPlayer(uuid).ifPresent(player -> {
            proxy.getServer(targetServer).ifPresent(server -> {
                player.createConnectionRequest(server).fireAndForget();
            });
        });
    }
}
