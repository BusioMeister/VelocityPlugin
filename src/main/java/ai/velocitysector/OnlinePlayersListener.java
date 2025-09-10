package ai.velocitysector;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import redis.clients.jedis.JedisPubSub;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class OnlinePlayersListener extends JedisPubSub {

    private final Gson gson = new Gson();
    private final Map<String, Set<String>> sectorOnlinePlayers = new ConcurrentHashMap<>();

    @Override
    public void onPMessage(String pattern, String channel, String message) {
        if (!channel.startsWith("sector-online:")) return;

        String sector = channel.substring("sector-online:".length());
        Map<String, String> map = gson.fromJson(message, new TypeToken<Map<String, String>>(){}.getType());
        Set<String> players = new HashSet<>(map.values());

        sectorOnlinePlayers.put(sector, players);
    }

    public Set<String> getAllOnlinePlayers() {
        Set<String> all = new HashSet<>();
        for (Set<String> players : sectorOnlinePlayers.values()) {
            all.addAll(players);
        }
        return all;
    }

    public Set<String> getOnlinePlayersInSector(String sector) {
        return sectorOnlinePlayers.getOrDefault(sector, Collections.emptySet());
    }
}