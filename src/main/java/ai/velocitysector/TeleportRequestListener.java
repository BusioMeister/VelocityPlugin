package ai.velocitysector;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPubSub;
import java.util.Optional;

public class TeleportRequestListener extends JedisPubSub {

    private final ProxyServer proxy;
    private final RedisManager redisManager;
    private final Gson gson = new Gson();

    public TeleportRequestListener(ProxyServer proxy, RedisManager redisManager) {
        this.proxy = proxy;
        this.redisManager = redisManager;
    }

    @Override
    public void onMessage(String channel, String message) {
        if (!channel.equals("aisector:tp_request")) return;

        JsonObject request = gson.fromJson(message, JsonObject.class);
        String adminName = request.get("adminName").getAsString();
        String targetName = request.get("targetName").getAsString();

        Optional<Player> adminOptional = proxy.getPlayer(adminName);
        if (!adminOptional.isPresent()) return;
        Player admin = adminOptional.get();

        Optional<Player> targetOptional = proxy.getPlayer(targetName);
        if (!targetOptional.isPresent()) {
            sendMessageToPlayer(adminName, "§cGracz o nicku '" + targetName + "' nie jest online.");
            return;
        }

        Player target = targetOptional.get();
        RegisteredServer adminServer = admin.getCurrentServer().get().getServer();
        RegisteredServer targetServer = target.getCurrentServer().get().getServer();

        try (Jedis jedis = redisManager.getJedis()) {
            if (adminServer.getServerInfo().getName().equals(targetServer.getServerInfo().getName())) {
                // Gracze są na tym samym serwerze -> wyślij polecenie teleportacji lokalnej
                JsonObject executeTeleport = new JsonObject();
                executeTeleport.addProperty("playerName", adminName);
                executeTeleport.addProperty("targetName", targetName);
                jedis.publish("aisector:tp_execute_local", executeTeleport.toString());
            } else {
                // Gracze są na różnych serwerach -> zleć transfer
                jedis.setex("player:tp_target:" + admin.getUniqueId(), 15, target.getUniqueId().toString());
                admin.createConnectionRequest(targetServer).fireAndForget();
            }
        }
    }

    private void sendMessageToPlayer(String playerName, String message) {
        try (Jedis jedis = redisManager.getJedis()) {
            JsonObject msgData = new JsonObject();
            msgData.addProperty("playerName", playerName);
            msgData.addProperty("message", message);
            jedis.publish("aisector:send_message", msgData.toString());
        }
    }
}