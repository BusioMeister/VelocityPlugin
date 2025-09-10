package ai.velocitysector;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;

import com.velocitypowered.api.proxy.server.RegisteredServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bson.Document;
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
    private final Gson gson = new Gson();

    public NetworkListener(ProxyServer proxy, RedisManager redisManager, MongoDBManager mongoDBManager, Map<UUID, UUID> tpaRequests, OnlinePlayersListener onlinePlayersListener) {
        this.proxy = proxy;
        this.redisManager = redisManager;
        this.onlinePlayersListener = onlinePlayersListener;
        this.mongoDBManager = mongoDBManager;
        this.tpaRequests = tpaRequests;
    }
    private final Map<String, JsonObject> sectorStats = new ConcurrentHashMap<>();


    @Override
    public void onMessage(String channel, String message) {
        // Kanał "sector-transfer" ma inny format (nie JSON), więc obsługujemy go osobno
        if (channel.equals("sector-transfer")) {
            handleTransfer(message);
            return; // Zakończ, aby nie próbować parsować jako JSON
        }

        // Wszystkie pozostałe kanały używają formatu JSON, więc parsujemy go raz
        JsonObject data = gson.fromJson(message, JsonObject.class);

        // 🔥 POPRAWIONA, JEDNA, CIĄGŁA STRUKTURA IF-ELSE IF
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
    private void handleSektorRequest(JsonObject data) {
        String requesterName = data.get("requesterName").getAsString();
        String targetName = data.get("targetName").getAsString();

        // Wykorzystujemy OnlinePlayersListener, który musi być dostępny w tej klasie
        // (upewnij się, że przekazujesz go w konstruktorze)
        String targetSector = onlinePlayersListener.getPlayerSector(targetName);

        if (targetSector != null) {
            String response = "§7Gracz §e" + targetName + " §7jest na sektorze §b" + targetSector;
            sendMessageToPlayer(requesterName, response);
        } else {
            sendMessageToPlayer(requesterName, "§cGracz o nicku '" + targetName + "' nie jest online w sieci.");
        }
    }

    // 🔥 NOWA METODA: Dodaj ją do klasy NetworkListener
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

        // 🔥 KLUCZOWA ZMIANA: Ustawiamy "znacznik", aby zmusić gracza do odrodzenia na spawnie sektora
        try (Jedis jedis = redisManager.getJedis()) {
            jedis.setex("player:force_spawn:" + targetPlayer.getUniqueId().toString(), 15, "true");
        }

        // Zleć transfer gracza
        targetPlayer.createConnectionRequest(serverOpt.get()).fireAndForget();

        // Zaktualizuj ostatni sektor gracza w bazie danych
        Document update = new Document("$set", new Document("sector", sectorName));
        mongoDBManager.updateOneByUuid("users", targetPlayer.getUniqueId().toString(), update);

        sendMessageToPlayer(requesterName, "§aWysłano gracza " + targetName + " na serwer " + sectorName + ".");
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
    private void handleGuiDataRequest(JsonObject data) {
        String uuid = data.get("uuid").getAsString();
        JsonArray responseArray = new JsonArray();

        // Przejdź przez wszystkie zarejestrowane serwery
        for (RegisteredServer server : proxy.getAllServers()) {
            String serverName = server.getServerInfo().getName();
            JsonObject serverData = new JsonObject();
            serverData.addProperty("name", serverName);

            Set<String> players = onlinePlayersListener.getOnlinePlayersInSector(serverName);
            JsonObject stats = sectorStats.get(serverName);

            // Sprawdź, czy serwer jest online na podstawie aktywności
            if (!players.isEmpty() || stats != null) {
                serverData.addProperty("isOnline", true);
                serverData.addProperty("players", players.size());
                // Użyj domyślnych wartości, jeśli statystyki jeszcze nie dotarły
                serverData.addProperty("tps", stats != null ? stats.get("tps").getAsString() : "?.??");
                serverData.addProperty("ram", stats != null ? stats.get("ram").getAsInt() : 0);
            } else {
                serverData.addProperty("isOnline", false);
            }
            responseArray.add(serverData);
        }

        // Wyślij odpowiedź na dedykowany kanał dla gracza
        try (Jedis jedis = redisManager.getJedis()) {
            jedis.publish("aisector:gui_data_response:" + uuid, responseArray.toString());
        }
    }
}
