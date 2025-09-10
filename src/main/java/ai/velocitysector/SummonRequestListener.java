package ai.velocitysector;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPubSub;
import java.util.Optional;

public class SummonRequestListener extends JedisPubSub {

    private final ProxyServer proxy;
    private final RedisManager redisManager;
    private final Gson gson = new Gson();

    public SummonRequestListener(ProxyServer proxy, RedisManager redisManager) {
        this.proxy = proxy;
        this.redisManager = redisManager;
    }

    @Override
    public void onMessage(String channel, String message) {
        if (!channel.equals("aisector:summon_request")) return;

        JsonObject request = gson.fromJson(message, JsonObject.class);
        String adminName = request.get("adminName").getAsString();
        String targetName = request.get("targetName").getAsString();
        JsonObject adminLocation = request.getAsJsonObject("adminLocation");

        Optional<Player> adminOptional = proxy.getPlayer(adminName);
        Optional<Player> targetOptional = proxy.getPlayer(targetName);

        if (!adminOptional.isPresent()) return; // Admin się wylogował

        if (!targetOptional.isPresent()) {
            sendMessageToPlayer(adminName, "§cGracz o nicku '" + targetName + "' nie jest online.");
            return;
        }

        Player admin = adminOptional.get();
        Player target = targetOptional.get();
        RegisteredServer adminServer = admin.getCurrentServer().get().getServer();

        try (Jedis jedis = redisManager.getJedis()) {
            // Zapisz w Redis docelową lokalizację dla przywołanego gracza
            jedis.setex("player:summon_location:" + target.getUniqueId(), 15, adminLocation.toString());
            // Zleć transfer przywołanego gracza na serwer admina
            target.createConnectionRequest(adminServer).fireAndForget();
        }
    }

    private void sendMessageToPlayer(String playerName, String message) {
        // Ta metoda jest zduplikowana z TeleportRequestListener, można ją wynieść do wspólnej klasy
        try (Jedis jedis = redisManager.getJedis()) {
            JsonObject msgData = new JsonObject();
            msgData.addProperty("playerName", playerName);
            msgData.addProperty("message", message);
            jedis.publish("aisector:send_message", msgData.toString());
        }
    }
}