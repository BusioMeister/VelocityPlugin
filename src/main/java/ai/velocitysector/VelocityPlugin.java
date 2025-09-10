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
import redis.clients.jedis.JedisPubSub;

@Plugin(id = "velocitysector", name = "VelocitySector", version = "1.0", description = "Plugin zarządzający przenoszeniem graczy między sektorami")
public class VelocityPlugin {
    private final ProxyServer proxy;
    private final Logger logger;
    private RedisManager redisManager;
    private MongoDBManager mongoDBManager;
    private OnlinePlayersListener onlinePlayersListener;
    private final Map<UUID, UUID> lastMessagerMap = new ConcurrentHashMap<>();
    @Inject
    public VelocityPlugin(ProxyServer proxy, Logger logger) {
        this.proxy = proxy;
        this.logger = logger;
    }


    @com.velocitypowered.api.event.Subscribe
    public void onProxyInitialize(ProxyInitializeEvent event) {
        logger.info("VelocitySector Plugin został uruchomiony!");

        ChannelIdentifier chatChannel = MinecraftChannelIdentifier.create("global", "chat");
        proxy.getChannelRegistrar().register(chatChannel);

        redisManager = new RedisManager("127.0.0.1", 6379);
        mongoDBManager = new MongoDBManager("mongodb://localhost:27017","users");

        // Poprawny blok
        // W metodzie onProxyInitialization()
        redisManager.subscribe(new TeleportRequestListener(proxy, redisManager), "aisector:tp_request");
        redisManager.subscribe(new TransferListener(proxy), "sector-transfer");
        redisManager.subscribe(new TransferListener(proxy),"sector-border-init");
        redisManager.subscribe(new SummonRequestListener(proxy, redisManager), "aisector:summon_request");
        onlinePlayersListener = new OnlinePlayersListener();
        redisManager.psubscribe(onlinePlayersListener, "sector-online:*");

        logger.info("Zarejestrowano listener do globalnego uzupełniania nicków.");
        logger.info("Nasłuchiwanie Redis na kanale 'sector-transfer' zostało rozpoczęte.");
        logger.info("Nasłuchiwanie Redis na kanale 'sector-border-init' zostało rozpoczęte.");
        logger.info("Nasłuchiwanie Redis na kanale 'sector-online' zostało rozpoczęte.");
        logger.info("Nasłuchiwanie Redis na kanale 'global:chat' zostało rozpoczęte.");

        proxy.getEventManager().register(this, new PlayerDisconectListener(mongoDBManager));
        proxy.getEventManager().register(this, new PlayerJoinListener(mongoDBManager, redisManager, proxy));
        proxy.getEventManager().register(this, new VelocityGlobalChat(proxy, logger));
        proxy.getEventManager().register(this, new TabCompleteListener(onlinePlayersListener));

        proxy.getCommandManager().register("msg", new MsgCommand(proxy, onlinePlayersListener, redisManager, getLastMessagerMap()));
        proxy.getCommandManager().register("r", new ReplyCommand(proxy, redisManager, getLastMessagerMap()));
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
}
