package ai.velocitysector;

import ai.aisector.shared.RedisChannels;
import com.google.inject.Inject;
import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.scheduler.ScheduledTask;
import net.kyori.adventure.text.Component;
import org.slf4j.Logger;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.math.BigDecimal;

@Plugin(id = "velocitysector", name = "VelocitySector", version = "2.0.0", description = "Plugin zarządzający przenoszeniem graczy między sektorami")
public class VelocityPlugin {
    private static final int MAX_REDIS_MESSAGE_CHARS = 1_048_576;

    private final ProxyServer proxy;
    private final Logger logger;

    private RedisManager redisManager;
    private MongoDBManager mongoDBManager;
    private PlayerSectorPersistence playerSectorPersistence;
    private OnlinePlayersListener onlinePlayersListener;
    private MaintenanceManager maintenanceManager;
    private WhitelistCommand whitelistCommand;
    private ScheduledTask globalPlayerPublisherTask;
    private volatile boolean shuttingDown;

    private final Map<java.util.UUID, PendingTpaRequest> tpaRequests = new ConcurrentHashMap<>();
    private final Map<String, String> lastMessagerMap = new ConcurrentHashMap<>();

    @Inject
    public VelocityPlugin(ProxyServer proxy, Logger logger) {
        this.proxy = proxy;
        this.logger = logger;
    }

    @Subscribe
    public void onProxyInitialize(ProxyInitializeEvent event) {
        logger.info("VelocitySector Plugin został uruchomiony!");
        String redisHost = System.getProperty("aisector.redis.host",
                System.getenv().getOrDefault("AISECTOR_REDIS_HOST", "127.0.0.1"));
        int redisPort = Integer.parseInt(System.getProperty("aisector.redis.port",
                System.getenv().getOrDefault("AISECTOR_REDIS_PORT", "6379")));
        String redisPassword = System.getProperty("aisector.redis.password",
                System.getenv().getOrDefault("AISECTOR_REDIS_PASSWORD", ""));
        redisManager = new RedisManager(redisHost, redisPort, redisPassword,
                message -> logger.warn("{}", message));
        String mongoUri = System.getProperty("aisector.mongodb.uri",
                System.getenv().getOrDefault("AISECTOR_MONGODB_URI", "mongodb://127.0.0.1:27017"));
        String mongoDatabase = System.getProperty("aisector.mongodb.database",
                System.getenv().getOrDefault("AISECTOR_MONGODB_DATABASE", "users"));
        mongoDBManager = new MongoDBManager(mongoUri, mongoDatabase);
        playerSectorPersistence = new PlayerSectorPersistence(mongoDBManager);
        onlinePlayersListener = new OnlinePlayersListener();
        redisManager.psubscribe(onlinePlayersListener, RedisChannels.ONLINE_PLAYERS_PATTERN);

        CommandMeta meta = proxy.getCommandManager()
                .metaBuilder("vwhitelist")
                .plugin(this)
                .aliases("vw", "whitelist")
                .build();
        whitelistCommand = new WhitelistCommand(proxy, this, redisManager, mongoDBManager);
        proxy.getCommandManager().register(meta, whitelistCommand);

        NetworkListener networkListener = new NetworkListener(proxy, redisManager, mongoDBManager, tpaRequests, onlinePlayersListener, logger) {};
        redisManager.subscribe(
                networkListener,
                RedisChannels.SECTOR_TRANSFER, RedisChannels.TPA_REQUEST, RedisChannels.TPA_ACCEPT,
                RedisChannels.LEGACY_TP_REQUEST, RedisChannels.SUMMON_REQUEST,
                RedisChannels.SECTOR_REQUEST, RedisChannels.SEND_REQUEST, RedisChannels.SECTOR_STATS,
                RedisChannels.GUI_DATA_REQUEST, RedisChannels.INVSEE_REQUEST, RedisChannels.ADMIN_TP_REQUEST,
                RedisChannels.ADMIN_LOCATION_RESPONSE, RedisChannels.BAN_BROADCAST,
                RedisChannels.BAN_KICK, RedisChannels.PACKET, RedisChannels.SAVE_PLAYER_DATA_ACK
        );


        proxy.getChannelRegistrar().register(VelocityGlobalChat.CHANNEL);
        proxy.getEventManager().register(this,
                new PlayerDisconnectListener(playerSectorPersistence, logger, tpaRequests, networkListener));
        proxy.getEventManager().register(this,
                new PlayerJoinVelocityListener(this, playerSectorPersistence, proxy, logger));
        proxy.getEventManager().register(this, new VelocityGlobalChat(proxy, logger));
        proxy.getEventManager().register(this, new TabCompleteListener(onlinePlayersListener));
        proxy.getEventManager().register(this, new BanLoginListener(redisManager, logger));
        proxy.getEventManager().register(this, new MaintenanceLoginListener(redisManager, logger));


        MaintenanceManager maintenance = new MaintenanceManager(this, redisManager, proxy, logger);
        maintenanceManager = maintenance;

        // 4) Publikacja globalnej listy graczy (jak dotąd)
        globalPlayerPublisherTask = proxy.getScheduler()
                .buildTask(this, new GlobalPlayerListPublisher(redisManager, onlinePlayersListener, logger))
                .repeat(3L, TimeUnit.SECONDS)
                .schedule();

        // 5) Subskrypcja sterowania przerwą techniczną
        redisManager.subscribe(new redis.clients.jedis.JedisPubSub() {
            @Override public void onMessage(String channel, String message) {
                if (!RedisChannels.MAINTENANCE_TOGGLE.equals(channel)) return;
                if (message == null || message.length() > MAX_REDIS_MESSAGE_CHARS) {
                    logger.warn("Ignoring empty or oversized maintenance toggle message");
                    return;
                }
                try {
                    com.google.gson.JsonObject json = com.google.gson.JsonParser.parseString(message).getAsJsonObject();
                    if (!json.has("action") || !json.get("action").isJsonPrimitive()
                            || !json.getAsJsonPrimitive("action").isString()) throw new IllegalArgumentException("action");
                    String action = json.get("action").getAsString();
                    String by = boundedString(json, "by", "Konsola", 64);
                    int seconds = boundedInteger(json, "countdown", 30, 0, 300);
                    if ("on".equals(action)) {
                        maintenance.startCountdown(by, seconds);
                    } else if ("off".equals(action) && (!json.has("countdown") || seconds == 0)) {
                        maintenance.disableMaintenance().whenComplete((disabled, error) -> {
                            if (error != null) logger.warn("Could not disable maintenance", error);
                            if (Boolean.TRUE.equals(disabled) && !shuttingDown) {
                                scheduleProxyTask(() -> {
                                    if (maintenance.isMaintenanceEnabled()) return;
                                    proxy.getAllPlayers().forEach(p -> p.sendMessage(
                                            net.kyori.adventure.text.Component.text(
                                                    "§aPrzerwa techniczna zakończona — whitelista wyłączona.")));
                                });
                            }
                        });
                    }
                } catch (RuntimeException exception) {
                    logger.warn("Ignoring malformed maintenance toggle message: {}", exception.getMessage());
                }
            }
        }, RedisChannels.MAINTENANCE_TOGGLE);

        // 6) Istniejące broadcasty/kicki z banów i kicków
        redisManager.subscribe(new redis.clients.jedis.JedisPubSub() {
            @Override public void onMessage(String channel, String message) {
                if (message == null || message.length() > MAX_REDIS_MESSAGE_CHARS) {
                    logger.warn("Ignoring empty or oversized ban control message on {}", channel);
                    return;
                }
                try {
                    if (RedisChannels.BAN_BROADCAST.equals(channel)) {
                        com.google.gson.JsonObject json = com.google.gson.JsonParser.parseString(message).getAsJsonObject();
                        String type = boundedString(json, "type", null, 16);
                        String name = boundedString(json, "name", null, 64);
                        String by = boundedString(json, "by", null, 64);
                        String reason = boundedString(json, "reason", null, 256);
                        if (!"kick".equals(type) && !"user".equals(type) && !"ip".equals(type))
                            throw new IllegalArgumentException("type");
                        String text;
                        if ("kick".equals(type)) {
                            text = "§c" + name + " został wyrzucony §8- §f" + reason + " §8by §f" + by;
                        } else {
                            if (!json.has("perm") || !json.get("perm").isJsonPrimitive()
                                    || !json.getAsJsonPrimitive("perm").isBoolean()) throw new IllegalArgumentException("perm");
                            boolean perm = json.get("perm").getAsBoolean();
                            long minutes = boundedLong(json, "minutes", 0, 0, Long.MAX_VALUE);
                            String time = perm ? "permanentnie" : (minutes + " min");
                            text = (type.equals("ip")
                                    ? ("§c" + name + " został zbanowany IP §7(" + time + ") §8- §f" + reason + " §8by §f" + by)
                                    : ("§c" + name + " został zbanowany §7(" + time + ") §8- §f" + reason + " §8by §f" + by));
                        }
                        if (!shuttingDown) proxy.getAllPlayers().forEach(p -> p.sendMessage(net.kyori.adventure.text.Component.text(text)));
                    } else if (RedisChannels.BAN_KICK.equals(channel)) {
                        com.google.gson.JsonObject json = com.google.gson.JsonParser.parseString(message).getAsJsonObject();
                        java.util.UUID uuid = java.util.UUID.fromString(json.get("uuid").getAsString());
                        String msg = boundedString(json, "message", null, 512);
                        if (!shuttingDown) proxy.getPlayer(uuid).ifPresent(p -> p.disconnect(net.kyori.adventure.text.Component.text(msg)));
                    }
                } catch (RuntimeException exception) {
                    logger.warn("Ignoring malformed ban message on channel {}: {}", channel, exception.getMessage());
                }
            }
        }, RedisChannels.BAN_BROADCAST, RedisChannels.BAN_KICK);

        logger.info("Uruchomiono cykliczne wysyłanie globalnej listy graczy.");
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        shuttingDown = true;
        if (globalPlayerPublisherTask != null) {
            globalPlayerPublisherTask.cancel();
            globalPlayerPublisherTask = null;
        }
        if (whitelistCommand != null) whitelistCommand.close();
        if (maintenanceManager != null) {
            maintenanceManager.close();
            maintenanceManager = null;
        }
        if (redisManager != null) {
            redisManager.close();
            logger.info("Zamknięto połączenie z Redis.");
        }
        if (mongoDBManager != null) {
            mongoDBManager.close();
            logger.info("Zamknięto połączenie z MongoDB.");
        }
    }

    public Map<String, String> getLastMessagerMap() { return lastMessagerMap; }
    public Map<java.util.UUID, PendingTpaRequest> getTpaRequests() { return tpaRequests; }

    private void scheduleProxyTask(Runnable task) {
        if (shuttingDown) return;
        try {
            proxy.getScheduler().buildTask(this, task).schedule();
        } catch (RuntimeException ignoredShutdown) {
            logger.debug("Skipping proxy task during shutdown");
        }
    }

    private static String boundedString(com.google.gson.JsonObject json, String field, String fallback, int max) {
        if (!json.has(field)) return fallback;
        if (!json.get(field).isJsonPrimitive() || !json.getAsJsonPrimitive(field).isString())
            throw new IllegalArgumentException(field);
        String value = json.get(field).getAsString();
        if (value.isBlank() || value.length() > max) throw new IllegalArgumentException(field);
        return value;
    }

    private static int boundedInteger(com.google.gson.JsonObject json, String field, int fallback, int min, int max) {
        long value = boundedLong(json, field, fallback, min, max);
        return (int) value;
    }

    private static long boundedLong(com.google.gson.JsonObject json, String field, long fallback, long min, long max) {
        if (!json.has(field)) return fallback;
        if (!json.get(field).isJsonPrimitive() || !json.getAsJsonPrimitive(field).isNumber())
            throw new IllegalArgumentException(field);
        final long value;
        try {
            value = json.get(field).getAsBigDecimal().longValueExact();
        } catch (ArithmeticException | NumberFormatException malformed) {
            throw new IllegalArgumentException(field);
        }
        if (value < min || value > max)
            throw new IllegalArgumentException(field);
        return value;
    }
}
