package ai.velocitysector;

import ai.velocitysector.redis.packet.*;
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
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
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

    private static final LegacyComponentSerializer LEGACY =
            LegacyComponentSerializer.legacySection();

    private Component lc(String msg) {
        return LEGACY.deserialize(msg);
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

        if (channel.equals("aisector:packet")) {
            // w NetworkListener#onMessage, w bloku: if (channel.equals("aisectorpacket")) { ... }

            PacketEnvelope env = gson.fromJson(message, PacketEnvelope.class);

            if (env.id == GuildTagUpdatePacket.ID) {
                JsonCodec<GuildTagUpdatePacket> codec = new JsonCodec<>(GuildTagUpdatePacket.class);
                GuildTagUpdatePacket pkt = codec.decode(env.payload);

                UUID viewerId;
                try {
                    viewerId = UUID.fromString(pkt.viewerUuid);
                } catch (Exception e) {
                    logger.warn("GuildTagUpdatePacket: invalid viewerUuid={}", pkt.viewerUuid);
                    return;
                }

                proxy.getPlayer(viewerId).ifPresentOrElse(viewer -> {
                    // serwer na którym aktualnie jest viewer (tam trzeba wykonać scoreboard/team)
                    String targetServer = viewer.getCurrentServer()
                            .map(sc -> sc.getServerInfo().getName())
                            .orElse(null);

                    if (targetServer == null) {
                        logger.warn("GuildTagUpdatePacket: viewer {} has no current server", viewer.getUsername());
                        return;
                    }

                    // publikujemy NA OSOBNY KANAŁ per-serwer, którego słuchają spigoty
                    String serverChannel = "aisectorpacket:" + targetServer;
                    logger.info("TAG-FWD viewer={} -> channel={}", viewer.getUsername(), serverChannel);

                    try (Jedis jedis = redisManager.getJedis()) {
                        jedis.publish(serverChannel, gson.toJson(env));
                    } catch (Exception e) {
                        logger.error("GuildTagUpdatePacket: publish failed to {}", serverChannel, e);
                    }
                }, () -> logger.warn("GuildTagUpdatePacket: viewer not online uuid={}", viewerId));

                return; // ważne: kończymy obsługę tego pakietu
            }
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
                    admin.sendMessage(
                            r("Gracz o nicku '")
                                    .append(y(targetName))
                                    .append(r("' nie jest online."))
                    ));
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
            adminOpt.get().sendMessage(
                    r("Wystąpił błąd: Serwer docelowy nie istnieje.")
            );
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

            // powiadom requestera (jeśli istnieje)
            if (requesterOpt.isPresent()) {
                sendMessageToPlayer(requesterName, "§cGracz o nicku '" + targetName + "' nie jest online w sieci.");
            }
            // opcjonalnie: jeśli requester nie istnieje, to i tak nic nie wyślesz

            return;
        }
        if (requesterName.equalsIgnoreCase(targetName)) {
            sendMessageToPlayer(requesterName, "§cNie możesz wysłać prośby do samego siebie.");
            return;
        }

        Player requester = requesterOpt.get();
        Player target = targetOpt.get();
        tpaRequests.put(target.getUniqueId(), requester.getUniqueId());
        logger.info("[TPA] Dodano prośbę od " + requester.getUsername() + " do " + target.getUsername() + ". Mapa próśb: " + tpaRequests);
        requester.sendMessage(g("Wysłano prośbę o teleportację do gracza ").append(y(target.getUsername())));


        target.sendMessage(
                prefixTpa()
                        .append(g("Gracz ").append(y(requester.getUsername()))
                                .append(g(" chce się do Ciebie przeteleportować. Wpisz "))
                                .append(gr("/tpaccept")
                                        .clickEvent(ClickEvent.runCommand("/tpaccept")))
                        ));

    }


    private void handleTpaAccept(JsonObject data) {
        String accepterName = data.get("accepter").getAsString();

        Optional<Player> accepterOpt = proxy.getPlayer(accepterName);
        if (!accepterOpt.isPresent()) return;

        Player accepter = accepterOpt.get();

        UUID requesterUuid = tpaRequests.remove(accepter.getUniqueId());
        if (requesterUuid == null) {
            accepter.sendMessage(
                    r("Nie masz żadnych oczekujących próśb.")
            );

            return;
        }

        proxy.getPlayer(requesterUuid).ifPresent(requester -> {

            Optional<ServerConnection> reqServerOpt = requester.getCurrentServer();
            Optional<ServerConnection> accServerOpt = accepter.getCurrentServer();
            if (!reqServerOpt.isPresent() || !accServerOpt.isPresent()) {
                logger.warn("[TPA] requester lub accepter nie ma currentServer ({} / {})",
                        requester.getUsername(), accepter.getUsername());
                return;
            }

            String reqServerName = reqServerOpt.get().getServerInfo().getName();
            String accServerName = accServerOpt.get().getServerInfo().getName();

            // 1) Ten sam serwer -> teleport lokalny (stary kanał zostaje)
            if (reqServerName.equals(accServerName)) {
                logger.info("[TPA] Gracze {} i {} są na tym samym serwerze ({}). Zlecam teleport lokalny.",
                        requester.getUsername(), accepter.getUsername(), reqServerName);

                try (Jedis jedis = redisManager.getJedis()) {
                    JsonObject loc = data.getAsJsonObject("location");
                    if (loc == null) return;

                    LocalTpaTeleportPacket p = new LocalTpaTeleportPacket();
                    p.playerToTeleportName = requester.getUsername();
                    p.world = loc.get("world").getAsString();
                    p.x = loc.get("x").getAsDouble();
                    p.y = loc.get("y").getAsDouble();
                    p.z = loc.get("z").getAsDouble();
                    p.yaw = loc.get("yaw").getAsFloat();
                    p.pitch = loc.get("pitch").getAsFloat();
                    p.message = "§aZostałeś przeteleportowany.";

                    JsonCodec<LocalTpaTeleportPacket> codec = new JsonCodec<>(LocalTpaTeleportPacket.class);
                    String payloadJson = codec.encode(p);
                    new RedisPacketPublisher().publish(jedis, "aisector:packet", p, payloadJson);


                }

                accepter.sendMessage(
                        gr("Zaakceptowałeś prośbę od ")
                                .append(y(requester.getUsername()))
                );

                requester.sendMessage(
                        gr("Gracz ")
                                .append(y(accepter.getUsername()))
                                .append(gr(" zaakceptował Twoją prośbę."))
                );

                return;
            }

            // 2) Różne serwery -> warmup (NOWY kanał aisector:packet)
            logger.info("[TPA] Gracze {} ({}) i {} ({}) są na różnych serwerach. Zlecam warmup pakietem.",
                    requester.getUsername(), reqServerName, accepter.getUsername(), accServerName);

            JsonObject loc = data.getAsJsonObject("location");
            if (loc == null) {
                logger.warn("[TPA] Brak pola location w payloadzie tpa_accept");
                return;
            }

            try (Jedis jedis = redisManager.getJedis()) {
                TpaInitiateWarmupPacket p = new TpaInitiateWarmupPacket();
                p.requesterName = requester.getUsername();
                p.targetServerName = accServerName;

                p.world = loc.get("world").getAsString();
                p.x = loc.get("x").getAsDouble();
                p.y = loc.get("y").getAsDouble();
                p.z = loc.get("z").getAsDouble();
                p.yaw = loc.get("yaw").getAsFloat();
                p.pitch = loc.get("pitch").getAsFloat();

                JsonCodec<TpaInitiateWarmupPacket> codec = new JsonCodec<>(TpaInitiateWarmupPacket.class);
                String payloadJson = codec.encode(p);

                new RedisPacketPublisher().publish(jedis, "aisector:packet", p, payloadJson);
            }

            accepter.sendMessage(
                    gr("Zaakceptowałeś prośbę od ")
                            .append(y(requester.getUsername()))
            );

            requester.sendMessage(
                    gr("Gracz ")
                            .append(y(accepter.getUsername()))
                            .append(gr(" zaakceptował Twoją prośbę."))
            );

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
                    admin.sendMessage(
                            r("Gracz o nicku '")
                                    .append(y(targetName))
                                    .append(r("' nie jest online."))
                    ));

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

            // NEW
            SendMessagePacket p = new SendMessagePacket(playerName, message);
            JsonCodec<SendMessagePacket> codec = new JsonCodec<>(SendMessagePacket.class);
            new RedisPacketPublisher().publish(jedis, "aisector:packet", p, codec.encode(p));

            // OLD (tymczasowo)
            JsonObject msgData = new JsonObject();
            msgData.addProperty("playerName", playerName);
            msgData.addProperty("message", message);
            //jedis.publish("aisector:send_message", msgData.toString());

        }

    }
    private Component g(String s) { // gray
        return Component.text(s, NamedTextColor.GRAY);
    }

    private Component y(String s) { // yellow
        return Component.text(s, NamedTextColor.YELLOW);
    }

    private Component r(String s) { // red
        return Component.text(s, NamedTextColor.RED);
    }

    private Component gr(String s) { // green
        return Component.text(s, NamedTextColor.GREEN);
    }
    private Component prefixTpa() {
        return g("[")
                .append(gr("TPA"))
                .append(g("] "));
    }


}