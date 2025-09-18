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
import org.slf4j.Logger;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPubSub;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public abstract class NetworkListener extends JedisPubSub {

    private final ProxyServer proxy;
    private final RedisManager redisManager;
    private final OnlinePlayersListener onlinePlayersListener;
    private final MongoDBManager mongoDBManager;
    private final Map<UUID, UUID> tpaRequests;
    private final Logger logger;

    private final Gson gson = new Gson();
    private final Map<String, JsonObject> sectorStats = new ConcurrentHashMap<>();


    public NetworkListener(ProxyServer proxy, RedisManager redisManager, MongoDBManager mongoDBManager, Map<UUID, UUID> tpaRequests, OnlinePlayersListener onlinePlayersListener, Logger logger) {
        this.proxy = proxy;
        this.redisManager = redisManager;
        this.onlinePlayersListener = onlinePlayersListener;
        this.mongoDBManager = mongoDBManager;
        this.tpaRequests = tpaRequests;
        this.logger = logger;
    }

    @Override
    public void onMessage(String channel, String message) {

        if (channel.equals("sector-transfer")) {
            handleTransfer(message);
            return;
        }

        JsonObject data;
        try {
            data = gson.fromJson(message, JsonObject.class);
        } catch (Exception e) {
            logger.error("Błąd podczas parsowania JSON z kanału " + channel, e);
            return;
        }


        // --- POSPRZĄTANA I UJEDNOLICONA SEKCJA DLA KOMEND TP ---
        if (channel.equals("aisector:admin_tp_request")) {
            handleAdminTeleportRequest(data);
        } else if (channel.equals("aisector:admin_location_response")) {
            handleLocationResponseAndTransfer(data);
        } else if (channel.equals("aisector:tpa_request")) {
            handleTpaRequest(data);
        } else if (channel.equals("aisector:tpa_accept")) {
            handleTpaAccept(data);
        } else if (channel.equals("aisector:summon_request")) {
            handleSummonRequest(data);
        } else if (channel.equals("aisector:sektor_request")) {
            handleSektorRequest(data);
        } else if (channel.equals("aisector:send_request")) {
            handleSendRequest(data);
        } else if (channel.equals("aisector:sector_stats")) {
            sectorStats.put(data.get("sectorName").getAsString(), data);
        } else if (channel.equals("aisector:gui_data_request")) {
            handleGuiDataRequest(data);
        } else if (channel.equals("aisector:invsee_request")) {
            handleInvseeRequest(data);
        }
    }
    private void handleAdminTeleportRequest(JsonObject data) {
        String adminUUID = data.get("adminUUID").getAsString();
        String targetName = data.get("targetName").getAsString();

        Optional<Player> targetOpt = proxy.getPlayer(targetName);
        if (!targetOpt.isPresent()) {
            proxy.getPlayer(UUID.fromString(adminUUID)).ifPresent(admin ->
                    admin.sendMessage(Component.text("§cGracz o nicku '" + targetName + "' nie jest online.")));
            return;
        }

        try (Jedis jedis = redisManager.getJedis()) {
            jedis.publish("aisector:get_location_for_admin_tp", data.toString());
            logger.info("[TP] Wysyłam prośbę o lokalizację gracza {} dla admina {}", targetName, adminUUID);
        }
    }

    // Metoda, która kończy proces teleportacji po otrzymaniu lokalizacji
    private void handleLocationResponseAndTransfer(JsonObject data) {
        String adminUUIDString = data.get("adminUUID").getAsString();
        UUID adminUUID = UUID.fromString(adminUUIDString);
        Optional<Player> adminOpt = proxy.getPlayer(adminUUID);

        if (!adminOpt.isPresent()) return;

        JsonObject locationJson = data.getAsJsonObject("location");
        String targetServerName = data.get("targetServerName").getAsString();
        Optional<RegisteredServer> targetServerOpt = proxy.getServer(targetServerName);

        if (!targetServerOpt.isPresent()) {
            adminOpt.get().sendMessage(Component.text("§cWystąpił błąd: Serwer docelowy nie istnieje."));
            return;
        }

        try (Jedis jedis = redisManager.getJedis()) {
            jedis.setex("player:final_teleport_target:" + adminUUIDString, 60, locationJson.toString());
        }

        adminOpt.get().createConnectionRequest(targetServerOpt.get()).fireAndForget();
        logger.info("[TP] Otrzymano lokalizację, przenoszę admina {} na serwer {}", adminOpt.get().getUsername(), targetServerName);
    }

    private void handleInvseeRequest(JsonObject data) {
        String adminName = data.get("adminName").getAsString();
        String targetName = data.get("targetName").getAsString();
        String targetSector = onlinePlayersListener.getPlayerSector(targetName);
        if (targetSector != null) {
            try (Jedis jedis = redisManager.getJedis()) {
                jedis.publish("aisector:invsee_get_data", data.toString());
            }
        } else {
            sendMessageToPlayer(adminName, "§cGracz o nicku '" + targetName + "' nie jest online w sieci.");
        }
    }

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
        target.sendMessage(Component.text("§7Gracz §e" + requester.getUsername() + " §7chce się do Ciebie przeteleportować. Wpisz ").append(Component.text("/tpaccept", NamedTextColor.GREEN).clickEvent(ClickEvent.runCommand("/tpaccept"))));
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
            Optional<ServerConnection> reqServerOpt = requester.getCurrentServer();
            Optional<ServerConnection> accServerOpt = accepter.getCurrentServer();
            if (reqServerOpt.isPresent() && accServerOpt.isPresent() && reqServerOpt.get().getServerInfo().getName().equals(accServerOpt.get().getServerInfo().getName())) {
                logger.info("[TPA] Gracze " + requester.getUsername() + " i " + accepter.getUsername() + " są na tym samym serwerze. Zlecam teleport lokalny.");
                try (Jedis jedis = redisManager.getJedis()) {
                    JsonObject localTpData = new JsonObject();
                    localTpData.addProperty("playerToTeleportName", requester.getUsername());
                    localTpData.add("targetLocation", data.getAsJsonObject("location"));
                    jedis.publish("aisector:tp_execute_local_tpa", localTpData.toString());
                }
            } else {
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

    private void initiateTransferWithDataSave(Player playerToTransfer, RegisteredServer destinationServer) {
        try (Jedis jedis = redisManager.getJedis()) {
            JsonObject saveDataRequest = new JsonObject();
            saveDataRequest.addProperty("uuid", playerToTransfer.getUniqueId().toString());
            jedis.publish("aisector:save_player_data", saveDataRequest.toString());
            logger.info("[Transfer] Zlecono zapis danych dla " + playerToTransfer.getUsername() + " przed transferem do " + destinationServer.getServerInfo().getName());
        }
        playerToTransfer.createConnectionRequest(destinationServer).fireAndForget();
    }

    private void handleSummonRequest(JsonObject data) {
        String adminUUID = data.get("adminUUID").getAsString();
        String targetName = data.get("targetName").getAsString();
        JsonObject adminLocation = data.getAsJsonObject("adminLocation");

        Optional<Player> adminOpt = proxy.getPlayer(UUID.fromString(adminUUID));
        Optional<Player> targetOpt = proxy.getPlayer(targetName);

        if (!adminOpt.isPresent() || !targetOpt.isPresent()) {
            proxy.getPlayer(UUID.fromString(adminUUID)).ifPresent(admin ->
                    admin.sendMessage(Component.text("§cGracz o nicku '" + targetName + "' nie jest online.")));
            return;
        }

        Player target = targetOpt.get();
        RegisteredServer adminServer = adminOpt.get().getCurrentServer().get().getServer();

        // Zapisujemy dane gracza docelowego do transferu
        try (Jedis jedis = redisManager.getJedis()) {
            // Ustawiamy lokalizację admina jako OSTATECZNY cel dla przywoływanego gracza
            jedis.setex("player:final_teleport_target:" + target.getUniqueId(), 60, adminLocation.toString());

            // Wysyłamy prośbę o zapisanie danych gracza (ekwipunku itp.)
            JsonObject saveDataRequest = new JsonObject();
            saveDataRequest.addProperty("uuid", target.getUniqueId().toString());
            jedis.publish("aisector:save_player_data", saveDataRequest.toString()); // Ten kanał musi być obsłużony na Spigocie
        }

        // Przenosimy gracza
        target.createConnectionRequest(adminServer).fireAndForget();
        logger.info("[Summon] Przywołuję gracza {} do admina {} na serwerze {}", target.getUsername(), adminOpt.get().getUsername(), adminServer.getServerInfo().getName());
    }

    private void handleSendRequest(JsonObject data) {
        String requesterName = data.get("requesterName").getAsString();
        String targetName = data.get("targetName").getAsString();
        String targetSectorName = data.get("targetSector").getAsString();

        Optional<Player> targetOpt = proxy.getPlayer(targetName);
        if (!targetOpt.isPresent()) {
            sendMessageToPlayer(requesterName, "§cGracz o nicku '" + targetName + "' nie jest online w sieci.");
            return;
        }

        Optional<RegisteredServer> serverOpt = proxy.getServer(targetSectorName);
        if (!serverOpt.isPresent()) {
            sendMessageToPlayer(requesterName, "§cSerwer o nazwie '" + targetSectorName + "' nie istnieje.");
            return;
        }

        Player targetPlayer = targetOpt.get();

        try (Jedis jedis = redisManager.getJedis()) {
            // --- KLUCZOWA ZMIANA ---
            // Zamiast "true", zapisujemy nazwę sektora docelowego.
            jedis.setex("player:force_sector_spawn:" + targetPlayer.getUniqueId(), 60, targetSectorName);
            // --- KONIEC ZMIANY ---

            // Prośba o zapis danych gracza pozostaje bez zmian
            JsonObject saveDataRequest = new JsonObject();
            saveDataRequest.addProperty("uuid", targetPlayer.getUniqueId().toString());
            jedis.publish("aisector:save_player_data", saveDataRequest.toString());
        }

        targetPlayer.createConnectionRequest(serverOpt.get()).fireAndForget();
        sendMessageToPlayer(requesterName, "§aWysłano gracza " + targetName + " na serwer " + targetSectorName + ".");
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