package ai.velocitysector;

import com.google.inject.Inject;
import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.proxy.ProxyServer;
import net.kyori.adventure.text.Component;
import org.slf4j.Logger;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Plugin(id = "velocitysector", name = "VelocitySector", version = "1.0", description = "Plugin zarządzający przenoszeniem graczy między sektorami")
public class VelocityPlugin {

    private final ProxyServer proxy;
    private final Logger logger;

    private RedisManager redisManager;
    private MongoDBManager mongoDBManager;
    private OnlinePlayersListener onlinePlayersListener;

    private final Map<java.util.UUID, java.util.UUID> tpaRequests = new ConcurrentHashMap<>();
    private final Map<String, String> lastMessagerMap = new ConcurrentHashMap<>();

    @Inject
    public VelocityPlugin(ProxyServer proxy, Logger logger) {
        this.proxy = proxy;
        this.logger = logger;
    }

    @Subscribe
    public void onProxyInitialize(ProxyInitializeEvent event) {
        logger.info("VelocitySector Plugin został uruchomiony!");
        redisManager = new RedisManager("127.0.0.1", 6379);
        mongoDBManager = new MongoDBManager("mongodb://localhost:27017", "users");
        onlinePlayersListener = new OnlinePlayersListener();
        redisManager.psubscribe(onlinePlayersListener, "sector-online:*");

        CommandMeta meta = proxy.getCommandManager()
                .metaBuilder("vwhitelist")
                .plugin(this)
                .aliases("vw", "whitelist")
                .build();
        proxy.getCommandManager().register(meta,
                new WhitelistCommand(proxy, redisManager, mongoDBManager));

        NetworkListener networkListener = new NetworkListener(proxy, redisManager, mongoDBManager, tpaRequests, onlinePlayersListener, logger) {};
        redisManager.subscribe(
                networkListener,
                "sector-transfer", "aisector:tpa_request", "aisector:tpa_accept",
                "aisector:tp_request", "aisector:summon_request",
                "aisector:sektor_request", "aisector:send_request", "aisector:sector_stats",
                "aisector:gui_data_request", "aisector:invsee_request", "aisector:admin_tp_request",
                "aisector:admin_location_response", "player:force_sector_spawn:", "aisector:ban_broadcast",
                "aisector:ban_kick"
        );


        proxy.getChannelRegistrar().register(VelocityGlobalChat.CHANNEL);
        proxy.getEventManager().register(this, new PlayerDisconectListener(mongoDBManager));
        proxy.getEventManager().register(this, new PlayerJoinVelocityListener(mongoDBManager, redisManager, proxy));
        proxy.getEventManager().register(this, new VelocityGlobalChat(proxy, logger));
        proxy.getEventManager().register(this, new TabCompleteListener(onlinePlayersListener));
        proxy.getEventManager().register(this, new BanLoginListener(redisManager, proxy, logger));
        proxy.getEventManager().register(this, new MaintenanceLoginListener(redisManager));


        MaintenanceManager maintenance = new MaintenanceManager(this, redisManager, proxy);

        // 4) Publikacja globalnej listy graczy (jak dotąd)
        proxy.getScheduler().buildTask(this, new GlobalPlayerListPublisher(redisManager, onlinePlayersListener))
                .repeat(3L, TimeUnit.SECONDS)
                .schedule();

        // 5) Subskrypcja sterowania przerwą techniczną
        redisManager.subscribe(new redis.clients.jedis.JedisPubSub() {
            @Override public void onMessage(String channel, String message) {
                if (!"aisector:maintenance_toggle".equals(channel)) return;
                com.google.gson.JsonObject json = com.google.gson.JsonParser.parseString(message).getAsJsonObject();
                String action = json.get("action").getAsString();
                String by = json.has("by") ? json.get("by").getAsString() : "Konsola";
                int seconds = json.has("countdown") ? json.get("countdown").getAsInt() : 30;
                if ("on".equals(action)) {
                    maintenance.startCountdown(by, seconds);
                } else if ("off".equals(action)) {
                    maintenance.disableMaintenance();
                    proxy.getAllPlayers().forEach(p ->
                            p.sendMessage(net.kyori.adventure.text.Component.text("§aPrzerwa techniczna zakończona — whitelista wyłączona."))
                    );
                }
            }
        }, "aisector:maintenance_toggle");

        // 6) Istniejące broadcasty/kicki z banów i kicków
        redisManager.subscribe(new redis.clients.jedis.JedisPubSub() {
            @Override public void onMessage(String channel, String message) {
                if ("aisector:ban_broadcast".equals(channel)) {
                    com.google.gson.JsonObject json = com.google.gson.JsonParser.parseString(message).getAsJsonObject();
                    String type = json.get("type").getAsString();
                    String name = json.get("name").getAsString();
                    String by = json.get("by").getAsString();
                    String reason = json.get("reason").getAsString();
                    String text;
                    if ("kick".equals(type)) {
                        text = "§c" + name + " został wyrzucony §8- §f" + reason + " §8by §f" + by;
                    } else {
                        boolean perm = json.get("perm").getAsBoolean();
                        long minutes = json.get("minutes").getAsLong();
                        String time = perm ? "permanentnie" : (minutes + " min");
                        text = (type.equals("ip")
                                ? ("§c" + name + " został zbanowany IP §7(" + time + ") §8- §f" + reason + " §8by §f" + by)
                                : ("§c" + name + " został zbanowany §7(" + time + ") §8- §f" + reason + " §8by §f" + by));
                    }
                    proxy.getAllPlayers().forEach(p -> p.sendMessage(net.kyori.adventure.text.Component.text(text)));
                } else if ("aisector:ban_kick".equals(channel)) {
                    com.google.gson.JsonObject json = com.google.gson.JsonParser.parseString(message).getAsJsonObject();
                    java.util.UUID uuid = java.util.UUID.fromString(json.get("uuid").getAsString());
                    String msg = json.get("message").getAsString();
                    proxy.getPlayer(uuid).ifPresent(p -> p.disconnect(net.kyori.adventure.text.Component.text(msg)));
                }
            }
        }, "aisector:ban_broadcast", "aisector:ban_kick");

        logger.info("Uruchomiono cykliczne wysyłanie globalnej listy graczy.");
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        if (redisManager != null) {
            redisManager.close();
            logger.info("Zamknięto połączenie z Redis.");
        }
    }

    public Map<String, String> getLastMessagerMap() { return lastMessagerMap; }
    public Map<java.util.UUID, java.util.UUID> getTpaRequests() { return tpaRequests; }
}
