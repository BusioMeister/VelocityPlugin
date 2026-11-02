package ai.velocitysector;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import redis.clients.jedis.JedisPubSub;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

public class OnlinePlayersListener extends JedisPubSub {
    private static final long DEFAULT_SNAPSHOT_TTL_NANOS = TimeUnit.SECONDS.toNanos(15);
    private static final int MAX_SNAPSHOT_CHARS = 65_536;
    private static final int MAX_SECTORS = 256;
    private static final int MAX_PLAYERS_PER_SECTOR = 1_000;

    private record SectorSnapshot(Set<String> players, long receivedAtNanos) { }

    private final Gson gson = new Gson();
    private final Map<String, SectorSnapshot> sectorOnlinePlayers = new ConcurrentHashMap<>();
    private final long snapshotTtlNanos;
    private final LongSupplier nanoTime;

    public OnlinePlayersListener() {
        this(DEFAULT_SNAPSHOT_TTL_NANOS, System::nanoTime);
    }

    OnlinePlayersListener(long snapshotTtlNanos, LongSupplier nanoTime) {
        if (snapshotTtlNanos <= 0) throw new IllegalArgumentException("Snapshot TTL must be positive");
        this.snapshotTtlNanos = snapshotTtlNanos;
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
    }

    @Override
    public void onPMessage(String pattern, String channel, String message) {
        if (channel == null || !channel.startsWith("sector-online:")
                || message == null || message.length() > MAX_SNAPSHOT_CHARS) return;

        String sector = channel.substring("sector-online:".length());
        if (sector.isBlank() || sector.length() > 128
                || (sectorOnlinePlayers.size() >= MAX_SECTORS && !sectorOnlinePlayers.containsKey(sector))) return;
        try {
            Map<String, String> map = gson.fromJson(message, new TypeToken<Map<String, String>>(){}.getType());
            if (map == null || map.size() > MAX_PLAYERS_PER_SECTOR) return;
            for (String playerName : map.values()) {
                if (playerName == null || !playerName.matches("[A-Za-z0-9_]{1,16}")) return;
            }
            Set<String> players = new HashSet<>(map.values());
            sectorOnlinePlayers.put(sector, new SectorSnapshot(Set.copyOf(players), nanoTime.getAsLong()));
        } catch (RuntimeException ignored) {
            // Ignore malformed presence messages and keep the last valid snapshot until it expires.
        }
    }

    public Set<String> getAllOnlinePlayers() {
        Set<String> all = new HashSet<>();
        long now = nanoTime.getAsLong();
        for (Map.Entry<String, SectorSnapshot> entry : sectorOnlinePlayers.entrySet()) {
            SectorSnapshot snapshot = entry.getValue();
            if (isExpired(snapshot, now)) {
                sectorOnlinePlayers.remove(entry.getKey(), snapshot);
            } else {
                all.addAll(snapshot.players());
            }
        }
        return all;
    }
    // W pliku OnlinePlayersListener.java na Velocity
    public String getPlayerSector(String playerName) {
        if (playerName == null || !playerName.matches("[A-Za-z0-9_]{1,16}")) return null;
        long now = nanoTime.getAsLong();
        String foundSector = null;
        for (Map.Entry<String, SectorSnapshot> entry : sectorOnlinePlayers.entrySet()) {
            SectorSnapshot snapshot = entry.getValue();
            if (isExpired(snapshot, now)) {
                sectorOnlinePlayers.remove(entry.getKey(), snapshot);
            } else if (snapshot.players().stream().anyMatch(name -> name.equalsIgnoreCase(playerName))) {
                if (foundSector != null && !foundSector.equals(entry.getKey())) return null;
                foundSector = entry.getKey();
            }
        }
        return foundSector;
    }

    public Set<String> getOnlinePlayersInSector(String sector) {
        SectorSnapshot snapshot = sectorOnlinePlayers.get(sector);
        if (snapshot == null) return Collections.emptySet();
        if (isExpired(snapshot, nanoTime.getAsLong())) {
            sectorOnlinePlayers.remove(sector, snapshot);
            return Collections.emptySet();
        }
        return snapshot.players();
    }

    private boolean isExpired(SectorSnapshot snapshot, long now) {
        return now - snapshot.receivedAtNanos() >= snapshotTtlNanos;
    }
}
