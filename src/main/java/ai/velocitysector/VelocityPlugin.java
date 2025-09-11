package ai.velocitysector;

import com.google.inject.Inject;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.proxy.ProxyServer;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import com.velocitypowered.api.proxy.messages.ChannelIdentifier;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import org.slf4j.Logger;

@Plugin(id = "velocitysector", name = "VelocitySector", version = "1.0", description = "Plugin zarządzający przenoszeniem graczy między sektorami")
public class VelocityPlugin {
    private final ProxyServer proxy;
    private final Logger logger;
    private RedisManager redisManager;
    private MongoDBManager mongoDBManager;
    private OnlinePlayersListener onlinePlayersListener;
    private final Map<UUID, UUID> lastMessagerMap = new ConcurrentHashMap<>();
    private final Map<UUID, UUID> tpaRequests = new ConcurrentHashMap<>();

    @Inject
    public VelocityPlugin(ProxyServer proxy, Logger logger) {
        this.proxy = proxy;
        this.logger = logger;
    }


    @com.velocitypowered.api.event.Subscribe
    public void onProxyInitialize(ProxyInitializeEvent event) {
        logger.info("VelocitySector Plugin został uruchomiony!");

        redisManager = new RedisManager("127.0.0.1", 6379);
        mongoDBManager = new MongoDBManager("mongodb://localhost:27017","users");

        onlinePlayersListener = new OnlinePlayersListener();
        redisManager.psubscribe(onlinePlayersListener, "sector-online:*");

        proxy.getChannelRegistrar().register(VelocityGlobalChat.CHANNEL);
        logger.info("Zarejestrowano kanał globalnego czatu: " + VelocityGlobalChat.CHANNEL.getId());

        NetworkListener networkListener = new NetworkListener(proxy, redisManager, mongoDBManager, getTpaRequests(), onlinePlayersListener,logger);
        redisManager.subscribe(networkListener,
                "sector-transfer", "aisector:tpa_request", "aisector:tpa_accept",
                "aisector:tp_request", "aisector:summon_request",
                "aisector:sektor_request", "aisector:send_request","aisector:sector_stats",
                "aisector:gui_data_request", "aisector:invsee_request"
        );

        proxy.getEventManager().register(this, new PlayerDisconectListener(mongoDBManager));
        proxy.getEventManager().register(this, new PlayerJoinVelocityListener(mongoDBManager, redisManager, proxy));
        proxy.getEventManager().register(this, new VelocityGlobalChat(proxy, logger));
        proxy.getEventManager().register(this, new TabCompleteListener(onlinePlayersListener));

        proxy.getCommandManager().register(proxy.getCommandManager().metaBuilder("msg").build(), new MsgCommand(proxy, onlinePlayersListener, redisManager, getLastMessagerMap()));
        proxy.getCommandManager().register(proxy.getCommandManager().metaBuilder("r").aliases("reply").build(), new ReplyCommand(proxy, redisManager, getLastMessagerMap()));

        proxy.getScheduler().buildTask(this, new GlobalPlayerListPublisher(redisManager, onlinePlayersListener))
                .repeat(3L, TimeUnit.SECONDS)
                .schedule();

        logger.info("Uruchomiono cykliczne wysyłanie globalnej listy graczy.");
    }


    @com.velocitypowered.api.event.Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        if (redisManager != null) {
            redisManager.close();
            logger.info("Zamknięto połączenie z Redis.");
        }
    }
    public Map<UUID, UUID> getLastMessagerMap() {
        return lastMessagerMap;
    }
    public Map<UUID, UUID> getTpaRequests() {
        return tpaRequests;
    }
}
