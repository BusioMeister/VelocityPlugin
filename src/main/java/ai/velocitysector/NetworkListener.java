package ai.velocitysector;

import ai.velocitysector.redis.packet.*;
import ai.aisector.shared.RedisChannels;
import ai.aisector.shared.PacketEnvelope;
import ai.aisector.shared.packet.*;
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
import redis.clients.jedis.JedisPubSub;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public abstract class NetworkListener extends JedisPubSub {

    private final ProxyServer proxy;
    private final RedisManager redisManager;
    private final OnlinePlayersListener onlinePlayersListener;
    private final MongoDBManager mongoDBManager;
    private final Map<UUID, PendingTpaRequest> tpaRequests;
    private final Logger logger;

    private final Gson gson = new Gson();
    private final Map<String, JsonObject> sectorStats = new ConcurrentHashMap<>();
    private final Map<String, PendingTransfer> pendingTransfers = new ConcurrentHashMap<>();
    private final Map<UUID, String> pendingTransferByPlayer = new ConcurrentHashMap<>();
    private final AsyncKeyedSequencer<UUID> transferRequestSequencer = new AsyncKeyedSequencer<>();
    private static final String DELETE_PENDING_TARGET_SCRIPT = "if redis.call('get', KEYS[1]) == ARGV[1] "
            + "then local deleted = 0; "
            + "if redis.call('get', KEYS[2]) == ARGV[2] then deleted = redis.call('del', KEYS[2]); end; "
            + "redis.call('del', KEYS[1]); return deleted; else return 0 end";
    private static final String STORE_PENDING_TARGET_SCRIPT = "redis.call('setex', KEYS[1], ARGV[1], ARGV[2]); "
            + "redis.call('setex', KEYS[2], ARGV[1], ARGV[3]); return 1";
    private static final String PUBLISH_SAVE_REQUEST_SCRIPT = "redis.call('setex', KEYS[1], ARGV[1], ARGV[2]); "
            + "local subscribers = redis.call('publish', ARGV[3], ARGV[4]); "
            + "if subscribers == 0 and redis.call('get', KEYS[1]) == ARGV[2] then "
            + "redis.call('del', KEYS[1]); end; return subscribers";
    private static final String CLEAR_SAVE_REQUEST_SCRIPT = "if redis.call('get', KEYS[1]) == ARGV[1] "
            + "then return redis.call('del', KEYS[1]); end; return 0";

    private record PendingTransfer(Player player, RegisteredServer destination,
                                   String requesterName, String completionMessage,
                                   String cleanupKey, String cleanupValue,
                                   String cleanupTokenKey, String cleanupToken) { }


    public NetworkListener(ProxyServer proxy, RedisManager redisManager, MongoDBManager mongoDBManager,
                           Map<UUID, PendingTpaRequest> tpaRequests,
                           OnlinePlayersListener onlinePlayersListener, Logger logger) {
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
        if (message == null || message.length() > 1_048_576) {
            logger.warn("Ignoring empty or oversized Redis message on channel {}", channel);
            return;
        }
        try {
            dispatchMessage(channel, message);
        } catch (RuntimeException exception) {
            logger.warn("Ignoring malformed Redis message on channel {}: {}", channel, exception.getMessage());
        }
    }

    private void dispatchMessage(String channel, String message) {
        if (channel.equals(RedisChannels.SECTOR_TRANSFER)) {
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
        if (data == null) {
            logger.warn("Ignoring empty JSON message on channel {}", channel);
            return;
        }

        if (channel.equals(RedisChannels.SAVE_PLAYER_DATA_ACK)) {
            handleSaveAcknowledgement(data);
            return;
        }

        if (channel.equals(RedisChannels.PACKET)) {
            try {
                PacketEnvelope env = gson.fromJson(message, PacketEnvelope.class);
                if (env == null || !env.isValid()) {
                    logger.warn("Odrzucono nieprawidłowy pakiet Redis.");
                    return;
                }

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
                    String serverChannel = RedisChannels.packetForServer(targetServer);
                    logger.info("TAG-FWD viewer={} -> channel={}", viewer.getUsername(), serverChannel);

                    redisManager.executeAsync(jedis -> jedis.publish(serverChannel, gson.toJson(env)))
                            .exceptionally(error -> {
                                logger.error("GuildTagUpdatePacket: publish failed to {}", serverChannel, error);
                                return null;
                            });
                }, () -> logger.warn("GuildTagUpdatePacket: viewer not online uuid={}", viewerId));

                    return; // ważne: kończymy obsługę tego pakietu
                }
            } catch (RuntimeException exception) {
                logger.warn("Nie udało się obsłużyć pakietu Redis: {}", exception.getMessage());
                return;
            }
        }

            // --- POSPRZĄTANA I UJEDNOLICONA SEKCJA DLA KOMEND TP ---
        if (channel.equals(RedisChannels.ADMIN_TP_REQUEST)) {
            handleAdminTeleportRequest(data);
        } else if (channel.equals(RedisChannels.ADMIN_LOCATION_RESPONSE)) {
            handleLocationResponseAndTransfer(data);
        } else if (channel.equals(RedisChannels.TPA_REQUEST)) {
            handleTpaRequest(data);
        } else if (channel.equals(RedisChannels.TPA_ACCEPT)) {
            handleTpaAccept(data);
        } else if (channel.equals(RedisChannels.SUMMON_REQUEST)) {
            handleSummonRequest(data);
        } else if (channel.equals(RedisChannels.SECTOR_REQUEST)) {
            handleSektorRequest(data);
        } else if (channel.equals(RedisChannels.SEND_REQUEST)) {
            handleSendRequest(data);
        } else if (channel.equals(RedisChannels.SECTOR_STATS)) {
            sectorStats.put(data.get("sectorName").getAsString(), data);
        } else if (channel.equals(RedisChannels.GUI_DATA_REQUEST)) {
            handleGuiDataRequest(data);
        } else if (channel.equals(RedisChannels.INVSEE_REQUEST)) {
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

        redisManager.executeAsync(jedis -> jedis.publish(RedisChannels.GET_LOCATION_FOR_ADMIN_TP, data.toString()))
                .whenComplete((ignored, error) -> {
            if (error != null) {
                logger.error("[TP] Nie udało się wysłać prośby o lokalizację dla {}", targetName, error);
                return;
            }
            logger.info("[TP] Wysyłam prośbę o lokalizację gracza {} dla admina {}", targetName, adminUUID);
        });
    }


    // Metoda, która kończy proces teleportacji po otrzymaniu lokalizacji
    private void handleLocationResponseAndTransfer(JsonObject data) {
        String adminUUIDString = data.get("adminUUID").getAsString();
        UUID adminUUID = UUID.fromString(adminUUIDString);
        Optional<Player> adminOpt = proxy.getPlayer(adminUUID);

        if (!adminOpt.isPresent()) return;

        JsonObject locationJson = data.getAsJsonObject("location");
        String targetServerName = data.get("targetServerName").getAsString();
        TpaLocation.parse(locationJson);
        if (targetServerName == null || !targetServerName.matches("[A-Za-z0-9_-]{1,64}")) {
            logger.warn("Ignoring admin teleport response with invalid backend name");
            return;
        }
        Optional<RegisteredServer> targetServerOpt = proxy.getServer(targetServerName);

        if (!targetServerOpt.isPresent()) {
            adminOpt.get().sendMessage(
                    r("Wystąpił błąd: Serwer docelowy nie istnieje.")
            );
            return;
        }

        String targetKey = "player:final_teleport_target:" + adminUUIDString;
        String cleanupToken = UUID.randomUUID().toString();
        String cleanupTokenKey = targetKey + ":request";
        Player admin = adminOpt.get();
        RegisteredServer targetServer = targetServerOpt.get();
        storePendingTargetAsync(targetKey, locationJson.toString(), cleanupTokenKey, cleanupToken)
                .whenComplete((ignored, error) -> {
                    if (proxy.getPlayer(admin.getUniqueId()).orElse(null) != admin) {
                        cleanupPendingTarget(new PendingTransfer(admin, targetServer, null, null,
                                targetKey, locationJson.toString(), cleanupTokenKey, cleanupToken));
                        return;
                    }
                    if (error != null) {
                        logger.error("Could not save admin teleport target for {}", admin.getUsername(), error);
                        admin.sendMessage(lc("§cNie udało się przygotować teleportacji."));
                        return;
                    }
                    requestSaveThenTransfer(admin, targetServer, admin.getUsername(), null,
                            targetKey, locationJson.toString(), cleanupTokenKey, cleanupToken, cleanupToken);
                    logger.info("[TP] Zlecono zapis przed przeniesieniem admina {} na serwer {}",
                            admin.getUsername(), targetServerName);
                });
    }

    private void handleInvseeRequest(JsonObject data) {
        String adminName = data.get("adminName").getAsString();
        String targetName = data.get("targetName").getAsString();
        String targetSector = onlinePlayersListener.getPlayerSector(targetName);
        if (targetSector != null) {
            redisManager.executeAsync(jedis -> jedis.publish(RedisChannels.INVSEE_GET_DATA, data.toString()))
                    .exceptionally(error -> {
                        logger.error("Could not request invsee data for {}", targetName, error);
                        return null;
                    });
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
        UUID targetUuid = target.getUniqueId();
        PendingTpaRequest pending = PendingTpaRequest.create(requester.getUniqueId());
        java.util.concurrent.atomic.AtomicBoolean acceptanceInProgress = new java.util.concurrent.atomic.AtomicBoolean();
        tpaRequests.compute(targetUuid, (ignored, current) -> {
            if (current != null && current.claimed()) {
                acceptanceInProgress.set(true);
                return current;
            }
            return pending;
        });
        if (acceptanceInProgress.get()) {
            requester.sendMessage(r("Odbiorca właśnie akceptuje poprzednią prośbę. Spróbuj ponownie za chwilę."));
            return;
        }
        CompletableFuture.delayedExecutor(60, TimeUnit.SECONDS).execute(() -> {
            java.util.concurrent.atomic.AtomicBoolean expired = new java.util.concurrent.atomic.AtomicBoolean();
            tpaRequests.computeIfPresent(targetUuid, (ignored, current) -> {
                if (!current.requestId().equals(pending.requestId())) return current;
                expired.set(true);
                return null;
            });
            if (!expired.get()) return;
            proxy.getPlayer(pending.requesterUuid()).ifPresent(expiredRequester ->
                    expiredRequester.sendMessage(r("Twoja prośba o teleportację wygasła.")));
        });
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

        UUID accepterUuid = accepter.getUniqueId();
        PendingTpaRequest pending = tpaRequests.get(accepterUuid);
        if (pending == null) {
            accepter.sendMessage(
                    r("Nie masz żadnych oczekujących próśb.")
            );

            return;
        }
        if (pending.isExpired(System.nanoTime())) {
            tpaRequests.remove(accepterUuid, pending);
            accepter.sendMessage(r("Ta prośba o teleportację już wygasła."));
            proxy.getPlayer(pending.requesterUuid()).ifPresent(requester ->
                    requester.sendMessage(r("Twoja prośba o teleportację wygasła.")));
            return;
        }
        if (pending.claimed()) {
            accepter.sendMessage(r("Ta prośba jest już obsługiwana."));
            return;
        }
        PendingTpaRequest claimed = pending.claim();
        if (!tpaRequests.replace(accepterUuid, pending, claimed)) {
            accepter.sendMessage(r("Prośba została już obsłużona lub wygasła."));
            return;
        }
        UUID requesterUuid = pending.requesterUuid();

        Optional<Player> requesterOpt = proxy.getPlayer(requesterUuid);
        if (requesterOpt.isEmpty()) {
            tpaRequests.remove(accepterUuid, claimed);
            accepter.sendMessage(r("Gracz składający prośbę nie jest już online."));
            return;
        }
        Player requester = requesterOpt.get();
        try {

            Optional<ServerConnection> reqServerOpt = requester.getCurrentServer();
            Optional<ServerConnection> accServerOpt = accepter.getCurrentServer();
            if (!reqServerOpt.isPresent() || !accServerOpt.isPresent()) {
                logger.warn("[TPA] requester lub accepter nie ma currentServer ({} / {})",
                        requester.getUsername(), accepter.getUsername());
                tpaRequests.replace(accepterUuid, claimed, pending);
                accepter.sendMessage(r("Nie udało się ustalić serwera gracza. Prośba pozostała aktywna."));
                return;
            }

            String reqServerName = reqServerOpt.get().getServerInfo().getName();
            String accServerName = accServerOpt.get().getServerInfo().getName();

            // 1) Ten sam serwer -> teleport lokalny (stary kanał zostaje)
            if (reqServerName.equals(accServerName)) {
                logger.info("[TPA] Gracze {} i {} są na tym samym serwerze ({}). Zlecam teleport lokalny.",
                        requester.getUsername(), accepter.getUsername(), reqServerName);

                TpaLocation loc = TpaLocation.parse(data.getAsJsonObject("location"));

                LocalTpaTeleportPacket p = new LocalTpaTeleportPacket();
                p.playerToTeleportName = requester.getUsername();
                p.world = loc.world();
                p.x = loc.x();
                p.y = loc.y();
                p.z = loc.z();
                p.yaw = loc.yaw();
                p.pitch = loc.pitch();
                p.message = "§aZostałeś przeteleportowany.";

                JsonCodec<LocalTpaTeleportPacket> codec = new JsonCodec<>(LocalTpaTeleportPacket.class);
                String payloadJson = codec.encode(p);
                String packetChannel = RedisChannels.packetForServer(reqServerName);
                CompletableFuture<Void> publish = redisManager.executeAsync(jedis -> {
                            if (new RedisPacketPublisher().publish(jedis, packetChannel, p, payloadJson) == 0L) {
                                throw new IllegalStateException("No server is subscribed to " + packetChannel);
                            }
                            return null;
                        });
                finishTpaAcceptance(accepter, requester, pending, claimed, publish);
                return;
            }

            // 2) Różne serwery -> warmup (NOWY kanał aisector:packet)
            logger.info("[TPA] Gracze {} ({}) i {} ({}) są na różnych serwerach. Zlecam warmup pakietem.",
                    requester.getUsername(), reqServerName, accepter.getUsername(), accServerName);

            TpaLocation loc = TpaLocation.parse(data.getAsJsonObject("location"));

            TpaInitiateWarmupPacket p = new TpaInitiateWarmupPacket();
            p.requesterName = requester.getUsername();
            p.targetServerName = accServerName;
            p.world = loc.world();
            p.x = loc.x();
            p.y = loc.y();
            p.z = loc.z();
            p.yaw = loc.yaw();
            p.pitch = loc.pitch();

            JsonCodec<TpaInitiateWarmupPacket> codec = new JsonCodec<>(TpaInitiateWarmupPacket.class);
            String payloadJson = codec.encode(p);
            String packetChannel = RedisChannels.packetForServer(reqServerName);
            CompletableFuture<Void> publish = redisManager.executeAsync(jedis -> {
                        if (new RedisPacketPublisher().publish(jedis, packetChannel, p, payloadJson) == 0L) {
                            throw new IllegalStateException("No server is subscribed to " + packetChannel);
                        }
                        return null;
                    });
            finishTpaAcceptance(accepter, requester, pending, claimed, publish);
        } catch (RuntimeException exception) {
            tpaRequests.replace(accepterUuid, claimed, pending);
            logger.warn("Could not prepare TPA acceptance for {}", requester.getUsername(), exception);
            accepter.sendMessage(r("Nie udało się przygotować teleportacji. Spróbuj ponownie."));
        }
    }

    private void finishTpaAcceptance(Player accepter, Player requester, PendingTpaRequest pending,
                                     PendingTpaRequest claimed, CompletableFuture<Void> publish) {
        publish.whenComplete((ignored, error) -> {
            if (error == null) {
                if (!tpaRequests.remove(accepter.getUniqueId(), claimed)) return;
                accepter.sendMessage(gr("Zaakceptowałeś prośbę od ").append(y(requester.getUsername())));
                requester.sendMessage(gr("Gracz ").append(y(accepter.getUsername()))
                        .append(gr(" zaakceptował Twoją prośbę.")));
                return;
            }

            logger.error("Could not publish TPA acceptance for {}", requester.getUsername(), error);
            if (pending.isExpired(System.nanoTime())) {
                tpaRequests.remove(accepter.getUniqueId(), claimed);
                accepter.sendMessage(r("Prośba o teleportację wygasła przed wykonaniem."));
            } else if (tpaRequests.replace(accepter.getUniqueId(), claimed, pending)) {
                accepter.sendMessage(r("Nie udało się zlecić teleportacji. Prośba pozostała aktywna."));
            }
        });
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
        Optional<RegisteredServer> adminServerOpt = adminOpt.get().getCurrentServer().map(ServerConnection::getServer);
        if (adminServerOpt.isEmpty()) {
            sendMessageToPlayer(adminOpt.get().getUsername(), "§cNie udało się ustalić serwera administratora.");
            return;
        }
        RegisteredServer adminServer = adminServerOpt.get();

        String targetKey = "player:final_teleport_target:" + target.getUniqueId();
        String cleanupToken = UUID.randomUUID().toString();
        String cleanupTokenKey = targetKey + ":request";
        String adminName = adminOpt.get().getUsername();
        storePendingTargetAsync(targetKey, adminLocation.toString(), cleanupTokenKey, cleanupToken)
                .whenComplete((ignored, error) -> {
                    if (error != null) {
                        logger.error("Could not save summon target for {}", target.getUsername(), error);
                        sendMessageToPlayer(adminName, "§cNie udało się przygotować przeniesienia gracza.");
                        return;
                    }
                    requestSaveThenTransfer(target, adminServer, adminName, null,
                            targetKey, adminLocation.toString(), cleanupTokenKey, cleanupToken, cleanupToken);
                    logger.info("[Summon] Zlecono przeniesienie gracza {} do administratora {} na serwerze {}",
                            target.getUsername(), adminName, adminServer.getServerInfo().getName());
                });
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

        String targetKey = "player:force_sector_spawn:" + targetPlayer.getUniqueId();
        String cleanupToken = UUID.randomUUID().toString();
        String cleanupTokenKey = targetKey + ":request";
        RegisteredServer destination = serverOpt.get();
        storePendingTargetAsync(targetKey, targetSectorName, cleanupTokenKey, cleanupToken)
                .whenComplete((ignored, error) -> {
                    if (error != null) {
                        logger.error("Could not save forced sector {} for {}", targetSectorName,
                                targetPlayer.getUsername(), error);
                        sendMessageToPlayer(requesterName, "§cNie udało się przygotować przeniesienia gracza.");
                        return;
                    }
                    requestSaveThenTransfer(targetPlayer, destination, requesterName,
                            "§aWysłano gracza " + targetName + " na serwer " + targetSectorName + ".",
                            targetKey, targetSectorName, cleanupTokenKey, cleanupToken, cleanupToken);
                });
    }

    private void handleTransfer(String message) {
        if (message == null || message.isBlank()) return;
        String[] data = message.split(":", 2);
        if (data.length != 2) return;
        UUID uuid;
        try {
            uuid = UUID.fromString(data[0]);
        } catch (IllegalArgumentException exception) {
            logger.warn("Ignoring transfer with invalid player UUID: {}", data[0]);
            return;
        }
        String targetServerName = data[1];
        if (!targetServerName.matches("[A-Za-z0-9_-]{1,64}")) {
            logger.warn("Ignoring transfer with invalid destination for {}", uuid);
            return;
        }
        Optional<RegisteredServer> destination = proxy.getServer(targetServerName);
        if (destination.isEmpty()) {
            logger.warn("Ignoring transfer to unknown destination {} for {}", targetServerName, uuid);
            return;
        }
        proxy.getPlayer(uuid).ifPresent(player -> {
            destination.ifPresent(server -> {
                player.createConnectionRequest(server).connect().whenComplete((result, error) -> {
                    if (error != null || result == null || !result.isSuccessful()) {
                        logger.warn("Transfer of {} to {} failed", player.getUsername(), targetServerName, error);
                        return;
                    }
                    Document update = new Document("$set", new Document("sector", targetServerName));
                    mongoDBManager.updateOneByUuidAsync("users", uuid.toString(), update)
                            .exceptionally(exception -> {
                                logger.error("Player {} moved to {}, but saving sector state to MongoDB failed",
                                        player.getUsername(), targetServerName, exception);
                                return null;
                            });
                });
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

    private void requestSaveThenTransfer(Player player, RegisteredServer destination,
                                         String requesterName, String completionMessage,
                                         String cleanupKey, String cleanupValue,
                                         String cleanupTokenKey, String cleanupToken, String requestId) {
        PendingTransfer pending = new PendingTransfer(player, destination, requesterName, completionMessage,
                cleanupKey, cleanupValue, cleanupTokenKey, cleanupToken);
        UUID playerUuid = player.getUniqueId();
        CompletableFuture<Long> publication;
        synchronized (pendingTransferByPlayer) {
            pendingTransfers.put(requestId, pending);
            String replacedRequestId = pendingTransferByPlayer.put(playerUuid, requestId);
            if (replacedRequestId != null) {
                PendingTransfer replaced = pendingTransfers.remove(replacedRequestId);
                if (replaced != null) {
                    cleanupPendingTarget(replaced);
                    clearSaveRequestToken(playerUuid, replacedRequestId);
                    logger.info("Replaced pending transfer {} for {} with newer request {}",
                            replacedRequestId, player.getUsername(), requestId);
                }
            }

            JsonObject request = new JsonObject();
            request.addProperty("requestId", requestId);
            request.addProperty("uuid", playerUuid.toString());
            String saveRequestKey = "player:transfer_save_request:" + playerUuid;
            publication = transferRequestSequencer.submit(playerUuid, () -> redisManager.executeAsync(jedis ->
                            jedis.eval(PUBLISH_SAVE_REQUEST_SCRIPT,
                                    java.util.List.of(saveRequestKey),
                                    java.util.List.of("30", requestId, RedisChannels.SAVE_PLAYER_DATA,
                                            request.toString())))
                    .thenApply(result -> ((Number) result).longValue()));
        }
        publication
                .whenComplete((subscribers, error) -> {
                    if (!requestId.equals(pendingTransferByPlayer.get(playerUuid))
                            || pendingTransfers.get(requestId) != pending) {
                        clearSaveRequestToken(playerUuid, requestId);
                        cleanupPendingTarget(pending);
                        return;
                    }
                    if (error != null || subscribers == null || subscribers == 0L) {
                        pendingTransfers.remove(requestId, pending);
                        pendingTransferByPlayer.remove(playerUuid, requestId);
                        clearSaveRequestToken(playerUuid, requestId);
                        cleanupPendingTarget(pending);
                        if (error != null) {
                            logger.error("Could not request a transfer save for {}", player.getUsername(), error);
                        } else {
                            logger.warn("No Paper backend is subscribed to transfer save requests for {}",
                                    player.getUsername());
                        }
                        notifyTransferFailure(pending, "§cNie udało się zapisać danych gracza. Transfer anulowany.");
                        return;
                    }

                    CompletableFuture.delayedExecutor(10, TimeUnit.SECONDS).execute(() -> {
                        PendingTransfer timedOut = pendingTransfers.get(requestId);
                        if (timedOut == null || timedOut != pending
                                || !pendingTransfers.remove(requestId, pending)) return;
                        if (timedOut != null) {
                            pendingTransferByPlayer.remove(playerUuid, requestId);
                            cleanupPendingTarget(timedOut);
                            clearSaveRequestToken(playerUuid, requestId);
                            logger.warn("Timed out waiting for transfer save acknowledgement for {}",
                                    player.getUsername());
                            notifyTransferFailure(timedOut,
                                    "§cSerwer nie potwierdził zapisu danych. Transfer anulowany.");
                        }
                    });
                });
    }

    private CompletableFuture<Object> storePendingTargetAsync(String targetKey, String targetValue,
                                                               String tokenKey, String token) {
        return redisManager.executeAsync(jedis -> jedis.eval(STORE_PENDING_TARGET_SCRIPT,
                java.util.List.of(targetKey, tokenKey), java.util.List.of("60", targetValue, token)));
    }

    private void handleSaveAcknowledgement(JsonObject acknowledgement) {
        if (acknowledgement == null || !acknowledgement.has("requestId")
                || !acknowledgement.get("requestId").isJsonPrimitive()
                || !acknowledgement.getAsJsonPrimitive("requestId").isString()) {
            logger.warn("Ignoring transfer save acknowledgement without requestId");
            return;
        }

        String requestId = acknowledgement.get("requestId").getAsString();
        if (requestId.isBlank() || requestId.length() > 128) {
            logger.warn("Ignoring transfer save acknowledgement with invalid requestId");
            return;
        }
        PendingTransfer pending = pendingTransfers.get(requestId);
        if (pending == null) {
            logger.debug("Ignoring unknown or expired transfer save acknowledgement {}", requestId);
            return;
        }
        UUID playerUuid = pending.player().getUniqueId();
        if (!acknowledgementMatchesPlayer(acknowledgement, playerUuid)) {
            logger.warn("Ignoring transfer save acknowledgement {} for mismatched player UUID", requestId);
            return;
        }
        if (!pendingTransfers.remove(requestId, pending)) return;
        if (!requestId.equals(pendingTransferByPlayer.get(playerUuid))) {
            logger.debug("Ignoring stale transfer save acknowledgement {} for {}", requestId,
                    pending.player().getUsername());
            clearSaveRequestToken(playerUuid, requestId);
            cleanupPendingTarget(pending);
            return;
        }

        boolean saved = acknowledgement.has("success") && acknowledgement.get("success").getAsBoolean();
        if (!saved) {
            pendingTransferByPlayer.remove(playerUuid, requestId);
            clearSaveRequestToken(playerUuid, requestId);
            cleanupPendingTarget(pending);
            logger.warn("Source server failed to save transfer data for {}", pending.player().getUsername());
            notifyTransferFailure(pending, "§cNie udało się zapisać danych gracza. Transfer anulowany.");
            return;
        }

        redisManager.executeAsync(jedis -> java.util.Objects.equals(
                        jedis.get(pending.cleanupTokenKey()), pending.cleanupToken())
                        && java.util.Objects.equals(jedis.get(pending.cleanupKey()), pending.cleanupValue()))
                .whenComplete((targetIsCurrent, error) -> {
                    if (error != null) {
                        if (pendingTransferByPlayer.remove(playerUuid, requestId)) {
                            cleanupPendingTarget(pending);
                            logger.error("Could not validate pending transfer target for {}",
                                    pending.player().getUsername(), error);
                            notifyTransferFailure(pending, "§cNie udało się bezpiecznie potwierdzić transferu.");
                        }
                        return;
                    }
                    boolean latestRequest = pendingTransferByPlayer.remove(playerUuid, requestId);
                    if (!Boolean.TRUE.equals(targetIsCurrent) || !latestRequest) {
                        logger.debug("Ignoring superseded transfer save acknowledgement {} for {}", requestId,
                                pending.player().getUsername());
                        cleanupPendingTarget(pending);
                        return;
                    }
                    startPendingTransfer(pending);
                });
    }

    static boolean acknowledgementMatchesPlayer(JsonObject acknowledgement, UUID playerUuid) {
        if (acknowledgement == null || playerUuid == null || !acknowledgement.has("uuid")
                || !acknowledgement.get("uuid").isJsonPrimitive()
                || !acknowledgement.getAsJsonPrimitive("uuid").isString()
                || !acknowledgement.has("success") || !acknowledgement.get("success").isJsonPrimitive()
                || !acknowledgement.getAsJsonPrimitive("success").isBoolean()) {
            return false;
        }
        try {
            return playerUuid.equals(UUID.fromString(acknowledgement.get("uuid").getAsString()));
        } catch (IllegalArgumentException invalidUuid) {
            return false;
        }
    }

    private void startPendingTransfer(PendingTransfer pending) {
        Player currentPlayer = proxy.getPlayer(pending.player().getUniqueId()).orElse(null);
        if (currentPlayer == null || currentPlayer != pending.player()) {
            cleanupPendingTarget(pending);
            logger.debug("Player {} disconnected before transfer could start", pending.player().getUsername());
            return;
        }

        currentPlayer.createConnectionRequest(pending.destination()).connect().whenComplete((result, error) -> {
            Player stillCurrent = proxy.getPlayer(currentPlayer.getUniqueId()).orElse(null);
            if (stillCurrent != currentPlayer) {
                cleanupPendingTarget(pending);
                return;
            }
            if (error != null || result == null || !result.isSuccessful()) {
                cleanupPendingTarget(pending);
                logger.warn("Transfer of {} to {} failed after save confirmation",
                        pending.player().getUsername(), pending.destination().getServerInfo().getName(), error);
                notifyTransferFailure(pending, "§cNie udało się połączyć z serwerem docelowym.");
                return;
            }
            if (pending.completionMessage() != null) {
                sendMessageToPlayer(pending.requesterName(), pending.completionMessage());
            }
            logger.info("Transfer of {} to {} completed after save confirmation", pending.player().getUsername(),
                    pending.destination().getServerInfo().getName());
        });
    }

    private void notifyTransferFailure(PendingTransfer pending, String message) {
        if (pending.requesterName() != null
                && !pending.requesterName().equalsIgnoreCase(pending.player().getUsername())) {
            sendMessageToPlayer(pending.requesterName(), message);
        } else {
            Player current = proxy.getPlayer(pending.player().getUniqueId()).orElse(null);
            if (current == pending.player()) current.sendMessage(lc(message));
        }
    }

    private void cleanupPendingTarget(PendingTransfer pending) {
        if (pending.cleanupKey() == null) {
            return;
        }
        redisManager.executeAsync(jedis -> jedis.eval(DELETE_PENDING_TARGET_SCRIPT,
                        java.util.List.of(pending.cleanupTokenKey(), pending.cleanupKey()),
                        java.util.List.of(pending.cleanupToken(), pending.cleanupValue())))
                .exceptionally(error -> {
                    logger.warn("Could not clear pending transfer key {}", pending.cleanupKey(), error);
                    return null;
                });
    }

    private void clearSaveRequestToken(UUID playerUuid, String requestId) {
        String key = "player:transfer_save_request:" + playerUuid;
        transferRequestSequencer.submit(playerUuid, () -> redisManager.executeAsync(jedis ->
                        jedis.eval(CLEAR_SAVE_REQUEST_SCRIPT,
                                java.util.List.of(key), java.util.List.of(requestId))))
                .exceptionally(error -> {
                    logger.warn("Could not clear transfer save token for {}", playerUuid, error);
                    return null;
                });
    }

    void cancelPendingTransfer(UUID playerUuid) {
        String requestId = pendingTransferByPlayer.remove(playerUuid);
        if (requestId == null) return;
        PendingTransfer pending = pendingTransfers.remove(requestId);
        clearSaveRequestToken(playerUuid, requestId);
        if (pending != null) cleanupPendingTarget(pending);
    }

    private void handleGuiDataRequest(JsonObject data) {
        String uuid = data.has("uuid") && data.get("uuid").isJsonPrimitive()
                ? data.get("uuid").getAsString() : null;
        if (uuid == null || uuid.length() > 36) {
            logger.warn("Ignoring sector GUI request with invalid UUID");
            return;
        }
        try {
            UUID.fromString(uuid);
        } catch (IllegalArgumentException invalidUuid) {
            logger.warn("Ignoring sector GUI request with invalid UUID");
            return;
        }
        JsonArray responseArray = new JsonArray();
        int includedServers = 0;
        for (RegisteredServer server : proxy.getAllServers()) {
            if (includedServers++ >= 54) break;
            String serverName = server.getServerInfo().getName();
            JsonObject serverData = new JsonObject();
            serverData.addProperty("name", serverName);
            Set<String> players = onlinePlayersListener.getOnlinePlayersInSector(serverName);
            JsonObject stats = sectorStats.get(serverName);
            if (!players.isEmpty() || stats != null) {
                serverData.addProperty("isOnline", true);
                serverData.addProperty("players", players.size());
                serverData.addProperty("tps", safeTps(stats));
                serverData.addProperty("ram", safeRam(stats));
            } else {
                serverData.addProperty("isOnline", false);
            }
            responseArray.add(serverData);
        }
        redisManager.executeAsync(jedis -> jedis.publish(RedisChannels.GUI_DATA_RESPONSE_PREFIX + uuid,
                        responseArray.toString()))
                .exceptionally(error -> {
                    logger.error("Could not publish sector GUI data for {}", uuid, error);
                    return null;
                });
    }

    private static String safeTps(JsonObject stats) {
        if (stats == null || !stats.has("tps") || stats.get("tps").isJsonNull()) return "?.??";
        try {
            String value = stats.get("tps").getAsString();
            return value.length() <= 16 ? value : "?.??";
        } catch (RuntimeException malformed) {
            return "?.??";
        }
    }

    private static int safeRam(JsonObject stats) {
        if (stats == null || !stats.has("ram") || !stats.get("ram").isJsonPrimitive()) return 0;
        try {
            int value = stats.get("ram").getAsInt();
            return Math.max(0, Math.min(value, 1_000_000));
        } catch (RuntimeException malformed) {
            return 0;
        }
    }



    private void sendMessageToPlayer(String playerName, String message) {
        SendMessagePacket packet = new SendMessagePacket(playerName, message);
        JsonCodec<SendMessagePacket> codec = new JsonCodec<>(SendMessagePacket.class);
        String payload = codec.encode(packet);
        redisManager.executeAsync(jedis -> {
                    new RedisPacketPublisher().publish(jedis, RedisChannels.PACKET, packet, payload);
                    return null;
                })
                .exceptionally(error -> {
                    logger.warn("Could not send message to {} via Redis", playerName, error);
                    return null;
                });

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
