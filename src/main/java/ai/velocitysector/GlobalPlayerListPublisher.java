package ai.velocitysector;

import com.google.gson.Gson;
import redis.clients.jedis.Jedis;

import java.util.Set;

public class GlobalPlayerListPublisher implements Runnable {

    private final RedisManager redisManager;
    private final OnlinePlayersListener onlinePlayersListener;
    private final Gson gson = new Gson();

    public GlobalPlayerListPublisher(RedisManager redisManager, OnlinePlayersListener onlinePlayersListener) {
        this.redisManager = redisManager;
        this.onlinePlayersListener = onlinePlayersListener;
    }

    @Override
    public void run() {
        try (Jedis jedis = redisManager.getJedis()) {
            // Pobierz aktualną, pełną listę graczy z całej sieci
            Set<String> allPlayers = onlinePlayersListener.getAllOnlinePlayers();
            // Przekonwertuj listę do formatu JSON
            String jsonPlayerList = gson.toJson(allPlayers);
            // Opublikuj listę na nowym kanale Redis
            jedis.publish("aisector:global_playerlist_update", jsonPlayerList);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}