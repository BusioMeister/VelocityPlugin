package ai.velocitysector;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;

import com.velocitypowered.api.proxy.server.RegisteredServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPubSub;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public class NetworkListener extends JedisPubSub {

    private final ProxyServer proxy;
    private final RedisManager redisManager;
    private final MongoDBManager mongoDBManager;
    private final Map<UUID, UUID> tpaRequests;
    private final Gson gson = new Gson();

    public NetworkListener(ProxyServer proxy, RedisManager redisManager, MongoDBManager mongoDBManager, Map<UUID, UUID> tpaRequests) {
        this.proxy = proxy;
        this.redisManager = redisManager;
        this.mongoDBManager = mongoDBManager;
        this.tpaRequests = tpaRequests;
    }

    @Override
    public void onMessage(String channel, String message) {
        // Używamy if-else if do obsługi różnych kanałów
        if (channel.equals("sector-transfer")) {
            handleTransfer(message);
        } else if (channel.equals("aisector:tpa_request")) {
            handleTpaRequest(gson.fromJson(message, JsonObject.class));
        } else if (channel.equals("aisector:tpa_accept")) {
            handleTpaAccept(gson.fromJson(message, JsonObject.class));
        } else if (channel.equals("aisector:tp_request")) {
            handleTeleportRequest(gson.fromJson(message, JsonObject.class));
        } else if (channel.equals("aisector:summon_request")) {
            handleSummonRequest(gson.fromJson(message, JsonObject.class));
        }
    }

    // Poniżej znajdują się metody przeniesione z Twoich starych listenerów
    // W pliku NetworkListener.java na Velocity

    private void handleTransfer(String message) {
        String[] data = message.split(":");
        if (data.length != 2) return;
        UUID uuid = UUID.fromString(data[0]);
        String targetServerName = data[1];

        proxy.getPlayer(uuid).ifPresent(player -> {
            proxy.getServer(targetServerName).ifPresent(server -> {
                // Najpierw zlecamy transfer
                player.createConnectionRequest(server).fireAndForget();

                // A zaraz potem aktualizujemy bazę danych
                org.bson.Document update = new org.bson.Document("$set", new org.bson.Document("sector", targetServerName));
                mongoDBManager.updateOneByUuid("users", uuid.toString(), update);
            });
        });
    }

    private void handleTpaRequest(JsonObject data) {
        String requesterName = data.get("requester").getAsString();
        String targetName = data.get("target").getAsString();
        Optional<Player> requesterOpt = proxy.getPlayer(requesterName);
        Optional<Player> targetOpt = proxy.getPlayer(targetName);

        if (!requesterOpt.isPresent() || !targetOpt.isPresent()) return;
        Player requester = requesterOpt.get();
        Player target = targetOpt.get();

        tpaRequests.put(target.getUniqueId(), requester.getUniqueId());
        requester.sendMessage(Component.text("§7Wysłano prośbę o teleportację do gracza §e" + target.getUsername()));
        target.sendMessage(
                Component.text("§7Gracz §e" + requester.getUsername() + " §7chce się do Ciebie przeteleportować. Wpisz ")
                        .append(Component.text("/tpaccept", NamedTextColor.GREEN).clickEvent(ClickEvent.runCommand("/tpaccept")))
        );
    }

    private void handleTpaAccept(JsonObject data) {
        String accepterName = data.get("accepter").getAsString();
        Optional<Player> accepterOpt = proxy.getPlayer(accepterName);

        if (!accepterOpt.isPresent()) return;
        Player accepter = accepterOpt.get();

        UUID requesterUuid = tpaRequests.remove(accepter.getUniqueId());
        if (requesterUuid == null) {
            accepter.sendMessage(Component.text("§cNie masz żadnych oczekujących próśb."));
            return;
        }

        proxy.getPlayer(requesterUuid).ifPresent(requester -> {
            JsonObject warmupData = new JsonObject();
            warmupData.addProperty("requesterName", requester.getUsername());
            warmupData.addProperty("targetName", accepter.getUsername());
            try (Jedis jedis = redisManager.getJedis()) {
                jedis.publish("aisector:tpa_initiate_warmup", warmupData.toString());
            }
            accepter.sendMessage(Component.text("§aZaakceptowałeś prośbę od §e" + requester.getUsername()));
        });
    }

    // Tę metodę skopiuj i wklej do NetworkListener.java
    private void handleTeleportRequest(JsonObject data) {
        String adminName = data.get("adminName").getAsString();
        String targetName = data.get("targetName").getAsString();

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

    // Tę metodę również skopiuj i wklej do NetworkListener.java
    private void handleSummonRequest(JsonObject data) {
        String adminName = data.get("adminName").getAsString();
        String targetName = data.get("targetName").getAsString();
        JsonObject adminLocation = data.getAsJsonObject("adminLocation");

        Optional<Player> adminOptional = proxy.getPlayer(adminName);
        Optional<Player> targetOptional = proxy.getPlayer(targetName);

        if (!adminOptional.isPresent()) return;

        if (!targetOptional.isPresent()) {
            sendMessageToPlayer(adminName, "§cGracz o nicku '" + targetName + "' nie jest online.");
            return;
        }

        Player admin = adminOptional.get();
        Player target = targetOptional.get();
        RegisteredServer adminServer = admin.getCurrentServer().get().getServer();

        try (Jedis jedis = redisManager.getJedis()) {
            jedis.setex("player:summon_location:" + target.getUniqueId(), 15, adminLocation.toString());
            target.createConnectionRequest(adminServer).fireAndForget();
        }
    }

    // Ta metoda pomocnicza jest używana przez obie powyższe, więc też ją skopiuj
    private void sendMessageToPlayer(String playerName, String message) {
        try (Jedis jedis = redisManager.getJedis()) {
            JsonObject msgData = new JsonObject();
            msgData.addProperty("playerName", playerName);
            msgData.addProperty("message", message);
            jedis.publish("aisector:send_message", msgData.toString());
        }
    }
}