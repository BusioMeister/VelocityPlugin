package ai.velocitysector;

import ai.aisector.shared.RedisChannels;
import com.google.gson.Gson;
import org.slf4j.Logger;

import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

public class GlobalPlayerListPublisher implements Runnable {

    private final RedisManager redisManager;
    private final OnlinePlayersListener onlinePlayersListener;
    private final Logger logger;
    private final Gson gson = new Gson();
    private final AtomicLong lastWarningNanos = new AtomicLong();

    public GlobalPlayerListPublisher(RedisManager redisManager, OnlinePlayersListener onlinePlayersListener,
                                     Logger logger) {
        this.redisManager = redisManager;
        this.onlinePlayersListener = onlinePlayersListener;
        this.logger = logger;
    }

    @Override
    public void run() {
        Set<String> allPlayers = onlinePlayersListener.getAllOnlinePlayers();
        String jsonPlayerList = gson.toJson(allPlayers);
        redisManager.executeAsync(jedis -> jedis.publish(RedisChannels.GLOBAL_PLAYER_LIST, jsonPlayerList))
                .exceptionally(error -> {
            long now = System.nanoTime();
            long previous = lastWarningNanos.get();
            if (now - previous >= 30_000_000_000L && lastWarningNanos.compareAndSet(previous, now)) {
                logger.warn("Could not publish the global player list to Redis; retrying: {}", error.getMessage());
            }
            return null;
        });
    }
}
