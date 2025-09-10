package ai.velocitysector;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bson.Document;
import org.slf4j.Logger; // WAŻNY IMPORT
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPubSub;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class NetworkListener extends JedisPubSub {

    private final ProxyServer proxy;
    private final RedisManager redisManager;
    private final OnlinePlayersListener onlinePlayersListener;
    private final MongoDBManager mongoDBManager;
    private final Map<UUID, UUID> tpaRequests;
    private final Logger logger; // WAŻNE: Dodajemy logger
    private final Gson gson = new Gson();

    // Zaktualizowany konstruktor
    public NetworkListener(ProxyServer proxy, RedisManager redisManager, MongoDBManager mongoDBManager, Map<UUID, UUID> tpaRequests, OnlinePlayersListener onlinePlayersListener, Logger logger) {
        this.proxy = proxy;
        this.redisManager = redisManager;
        this.onlinePlayersListener = onlinePlayersListener;
        this.mongoDBManager = mongoDBManager;
        this.tpaRequests = tpaRequests;
        this.logger = logger; // Zapisujemy logger
    }
    private final Map<String, JsonObject> sectorStats = new ConcurrentHashMap<>();

    @Override
    public void onMessage(String channel, String message) {
        // ... (reszta metody onMessage bez zmian)
        if (channel.equals("sector-transfer")) {
            handleTransfer(message);
            return;
        }

        JsonObject data = gson.fromJson(message, JsonObject.class);

        if (channel.equals("aisector:tpa_request")) {
            handleTpaRequest(data);
        } else if (channel.equals("aisector:tpa_accept")) {
            handleTpaAccept(data);
        } else if (channel.equals("aisector:tp_request")) {
            handleTeleportRequest(data);
        } else if (channel.equals("aisector:summon_request")) {
            handleSummonRequest(data);
        } else if (channel.equals("aisector:sektor_request")) {
            handleSektorRequest(data);
        } else if (channel.equals("aisector:send_request")) {
            handleSendRequest(data);
        } else if (channel.equals("aisector:sector_stats")) {
            String sectorName = data.get("sectorName").getAsString();
            sectorStats.put(sectorName, data);
        } else if (channel.equals("aisector:gui_data_request")) {
            handleGuiDataRequest(data);
        }
    }

    // 🔥 POPRAWIONA I DODANA DIAGNOSTYKA
    private void handleTpaRequest(JsonObject data) {
        String requesterName = data.get("requester").getAsString();
        String targetName = data.get("target").getAsString();
        Optional<Player> requesterOpt = proxy.getPlayer(requesterName);
        Optional<Player> targetOpt = proxy.getPlayer(targetName);

        if (!requesterOpt.isPresent() || !targetOpt.isPresent()) {
            logger.warn("[TPA] Nie znaleziono gracza wysyłającego lub docelowego.");
            return;
        }
        Player requester = requesterOpt.get();
        Player target = targetOpt.get();

        tpaRequests.put(target.getUniqueId(), requester.getUniqueId());
        logger.info("[TPA] Dodano prośbę od " + requester.getUsername() + " do " + target.getUsername() + ". Mapa próśb: " + tpaRequests);

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

            // 🔥 TUTAJ JEST KLUCZOWA POPRAWKA! 🔥
            // Zmieniamy prosty warunek .equals() na porównanie nazw serwerów,
            // co jest znacznie bardziej niezawodne.
            Optional<ServerConnection> reqServerOpt = requester.getCurrentServer();
            Optional<ServerConnection> accServerOpt = accepter.getCurrentServer();

            if (reqServerOpt.isPresent() && accServerOpt.isPresent() &&
                    reqServerOpt.get().getServerInfo().getName().equals(accServerOpt.get().getServerInfo().getName())) {

                // Gracze są na tym samym serwerze -> wykonaj teleport lokalny
                logger.info("[TPA] Gracze " + requester.getUsername() + " i " + accepter.getUsername() + " są na tym samym serwerze. Zlecam teleport lokalny.");
                try (Jedis jedis = redisManager.getJedis()) {
                    JsonObject localTpData = new JsonObject();
                    localTpData.addProperty("playerToTeleportName", requester.getUsername());
                    localTpData.add("targetLocation", data.getAsJsonObject("location"));

                    jedis.publish("aisector:tp_execute_local_tpa", localTpData.toString());
                }
            } else {
                // Gracze są na różnych serwerach -> uruchom WARMUP
                logger.info("[TPA] Gracze " + requester.getUsername() + " i " + accepter.getUsername() + " są na różnych serwerach. Zlecam warmup.");
                try (Jedis jedis = redisManager.getJedis()) {
                    JsonObject warmupData = new JsonObject();
                    warmupData.addProperty("requesterName", requester.getUsername());
                    warmupData.add("targetLocation", data.getAsJsonObject("location"));
                    warmupData.addProperty("targetServerName", accepter.getCurrentServer().get().getServerInfo().getName());

                    jedis.publish("aisector:tpa_initiate_warmup", warmupData.toString());
                }
            }

            accepter.sendMessage(Component.text("§aZaakceptowałeś prośbę od §e" + requester.getUsername()));
            requester.sendMessage(Component.text("§aGracz §e" + accepter.getUsername() + " §azaakceptował Twoją prośbę."));
        });
    }

    // 🔥 CAŁKOWICIE NAPRAWIONA LOGIKA /TP
    private void handleTeleportRequest(JsonObject data) {
        String adminName = data.get("adminName").getAsString();
        String targetName = data.get("targetName").getAsString();

        Optional<Player> adminOpt = proxy.getPlayer(adminName);
        if (!adminOpt.isPresent()) return;
        Player admin = adminOpt.get();

        Optional<Player> targetOpt = proxy.getPlayer(targetName);
        if (!targetOpt.isPresent()) {
            sendMessageToPlayer(adminName, "§cGracz o nicku '" + targetName + "' nie jest online.");
            return;
        }
        Player target = targetOpt.get();

        // Jeśli są na tym samym serwerze, Bukkit sobie poradzi
        if (admin.getCurrentServer().equals(target.getCurrentServer())) {
            try (Jedis jedis = redisManager.getJedis()) {
                JsonObject executeTeleport = new JsonObject();
                executeTeleport.addProperty("playerName", adminName);
                executeTeleport.addProperty("targetName", targetName);
                jedis.publish("aisector:tp_execute_local", executeTeleport.toString());
            }
            return;
        }

        // Jeśli są na różnych serwerach, zlecamy transfer i zapisujemy CEL
        RegisteredServer targetServer = target.getCurrentServer().get().getServer();
        try (Jedis jedis = redisManager.getJedis()) {
            // Zapisujemy UUID celu, aby PlayerJoinListener na serwerze docelowym wiedział, do kogo teleportować
            jedis.setex("player:tp_target_uuid:" + admin.getUniqueId(), 15, target.getUniqueId().toString());
        }

        initiateTransferWithDataSave(admin, targetServer);
    }

    // Reszta metod pozostaje bez zmian, wklejam je dla kompletności
    private void initiateTransferWithDataSave(Player playerToTransfer, RegisteredServer destinationServer) {
        Optional<RegisteredServer> currentServer = playerToTransfer.getCurrentServer().map(s -> s.getServer());
        if (currentServer.isPresent() && currentServer.get().equals(destinationServer)) {
            // Jeśli gracz jest już na serwerze docelowym (np. /tpa do kogoś na tym samym serwerze),
            // nie wykonuj transferu, tylko wyślij prośbę o teleport lokalny
            logger.info("[Transfer] Gracz " + playerToTransfer.getUsername() + " jest już na serwerze docelowym. Zlecam teleport lokalny.");
            try (Jedis jedis = redisManager.getJedis()) {
                JsonObject teleportData = new JsonObject();
                teleportData.addProperty("playerToTeleportUUID", playerToTransfer.getUniqueId().toString());
                teleportData.addProperty("targetLocationKey", "player:teleport_location:" + playerToTransfer.getUniqueId());
                jedis.publish("aisector:tp_execute_local_location", teleportData.toString());
            }
            return;
        }

        try (Jedis jedis = redisManager.getJedis()) {
            JsonObject saveDataRequest = new JsonObject();
            saveDataRequest.addProperty("uuid", playerToTransfer.getUniqueId().toString());
            jedis.publish("aisector:save_player_data", saveDataRequest.toString());
            logger.info("[Transfer] Zlecono zapis danych dla " + playerToTransfer.getUsername() + " przed transferem do " + destinationServer.getServerInfo().getName());
        }
        playerToTransfer.createConnectionRequest(destinationServer).fireAndForget();
    }


    // Upewnij się, że masz te metody wklejone z poprzedniej wersji.
    // Jeśli ich brakuje, skopiuj je z pliku, który ostatnio Ci wysłałem.
    // Dla pewności wklejam je poniżej:
    private void handleSummonRequest(JsonObject data) {
        String adminName = data.get("adminName").getAsString();
        String targetName = data.get("targetName").getAsString();
        Optional<Player> adminOpt = proxy.getPlayer(adminName);
        Optional<Player> targetOpt = proxy.getPlayer(targetName);
        if (!adminOpt.isPresent() || !targetOpt.isPresent()) {
            sendMessageToPlayer(adminName, "§cGracz nie jest online.");
            return;
        }
        Player admin = adminOpt.get();
        Player target = targetOpt.get();
        RegisteredServer adminServer = admin.getCurrentServer().get().getServer();
        try (Jedis jedis = redisManager.getJedis()) {
            JsonObject adminLocation = data.getAsJsonObject("adminLocation");
            jedis.setex("player:teleport_location:" + target.getUniqueId(), 15, adminLocation.toString());
        }
        initiateTransferWithDataSave(target, adminServer);
    }
    private void handleSendRequest(JsonObject data) {
        String requesterName = data.get("requesterName").getAsString();
        String targetName = data.get("targetName").getAsString();
        String sectorName = data.get("targetSector").getAsString();
        Optional<Player> targetOpt = proxy.getPlayer(targetName);
        if (!targetOpt.isPresent()) {
            sendMessageToPlayer(requesterName, "§cGracz o nicku '" + targetName + "' nie jest online.");
            return;
        }
        Optional<RegisteredServer> serverOpt = proxy.getServer(sectorName);
        if (!serverOpt.isPresent()) {
            sendMessageToPlayer(requesterName, "§cSerwer o nazwie '" + sectorName + "' nie istnieje.");
            return;
        }
        Player targetPlayer = targetOpt.get();
        try (Jedis jedis = redisManager.getJedis()) {
            jedis.setex("player:force_spawn:" + targetPlayer.getUniqueId().toString(), 15, "true");
        }
        initiateTransferWithDataSave(targetPlayer, serverOpt.get());
        Document update = new Document("$set", new Document("sector", sectorName));
        mongoDBManager.updateOneByUuid("users", targetPlayer.getUniqueId().toString(), update);
        sendMessageToPlayer(requesterName, "§aWysłano gracza " + targetName + " na serwer " + sectorName + ".");
    }
    private void handleTransfer(String message) {
        String[] data = message.split(":");
        if (data.length != 2) return;
        UUID uuid = UUID.fromString(data[0]);
        String targetServerName = data[1];
        proxy.getPlayer(uuid).ifPresent(player -> {
            proxy.getServer(targetServerName).ifPresent(server -> {
                player.createConnectionRequest(server).fireAndForget();
                Document update = new Document("$set", new Document("sector", targetServerName));
                mongoDBManager.updateOneByUuid("users", uuid.toString(), update);
            });
        });
    }
    private void handleSektorRequest(JsonObject data) {
        String requesterName = data.get("requesterName").getAsString();
        String targetName = data.get("targetName").getAsString();
        String targetSector = onlinePlayersListener.getPlayerSector(targetName);
        if (targetSector != null) {
            sendMessageToPlayer(requesterName, "§7Gracz §e" + targetName + " §7jest na sektorze §b" + targetSector);
        } else {
            sendMessageToPlayer(requesterName, "§cGracz o nicku '" + targetName + "' nie jest online w sieci.");
        }
    }
    private void handleGuiDataRequest(JsonObject data) {
        String uuid = data.get("uuid").getAsString();
        JsonArray responseArray = new JsonArray();
        for (RegisteredServer server : proxy.getAllServers()) {
            String serverName = server.getServerInfo().getName();
            JsonObject serverData = new JsonObject();
            serverData.addProperty("name", serverName);
            Set<String> players = onlinePlayersListener.getOnlinePlayersInSector(serverName);
            JsonObject stats = sectorStats.get(serverName);
            if (!players.isEmpty() || stats != null) {
                serverData.addProperty("isOnline", true);
                serverData.addProperty("players", players.size());
                serverData.addProperty("tps", stats != null ? stats.get("tps").getAsString() : "?.??");
                serverData.addProperty("ram", stats != null ? stats.get("ram").getAsInt() : 0);
            } else {
                serverData.addProperty("isOnline", false);
            }
            responseArray.add(serverData);
        }
        try (Jedis jedis = redisManager.getJedis()) {
            jedis.publish("aisector:gui_data_response:" + uuid, responseArray.toString());
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