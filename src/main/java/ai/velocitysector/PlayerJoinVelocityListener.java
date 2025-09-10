package ai.velocitysector;

import com.mongodb.client.model.Filters;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import org.bson.Document;
import redis.clients.jedis.Jedis;

import java.util.Optional;

public class PlayerJoinVelocityListener {
    private final MongoDBManager database;
    private final RedisManager redisManager; // Potrzebujemy go z powrotem
    private final ProxyServer proxy;

    // Zaktualizowany konstruktor
    public PlayerJoinVelocityListener(MongoDBManager database, RedisManager redisManager, ProxyServer proxy) {
        this.database = database;
        this.redisManager = redisManager;
        this.proxy = proxy;
    }

    @Subscribe
    public void onServerPostConnect(ServerPostConnectEvent event) {
        Player player = event.getPlayer();

        // Uruchom tę logikę TYLKO przy pierwszym połączeniu z proxy,
        // ignoruj normalne transfery między serwerami.
        if (event.getPreviousServer() != null) {
            return;
        }

        String uuidStr = player.getUniqueId().toString();
        String playerName = player.getUsername();
        String targetSectorName;

        // 🔥 TWOJA KLUCZOWA LOGIKA: Czyszczenie starych danych z Redis
        try (Jedis jedis = redisManager.getJedis()) {
            jedis.del("player:data:" + uuidStr);
            System.out.println("Wyczyszczono klucz 'player:data' dla gracza: " + playerName);
        } catch (Exception e) {
            System.out.println("Błąd podczas czyszczenia danych gracza w Redis: " + e.getMessage());
        }

        // Reszta logiki pozostaje bez zmian...
        Document playerDoc = database.findOne("users", Filters.eq("uuid", uuidStr));

        if (playerDoc != null) {
            targetSectorName = playerDoc.getString("sector");
            if (targetSectorName == null || targetSectorName.isEmpty()) {
                targetSectorName = "Sector1";
            }
        } else {
            targetSectorName = "Sector1";
            Document newPlayerDoc = new Document("uuid", uuidStr)
                    .append("name", playerName)
                    .append("sector", targetSectorName);
            database.insertOne("users", newPlayerDoc);
        }

        String currentServerName = player.getCurrentServer().get().getServerInfo().getName();
        if (!currentServerName.equals(targetSectorName)) {
            Optional<RegisteredServer> targetServer = proxy.getServer(targetSectorName);
            if (targetServer.isPresent()) {
                player.createConnectionRequest(targetServer.get()).fireAndForget();
                System.out.println("Korygowanie serwera dla " + playerName + ". Przenoszę na: " + targetSectorName);
            }
        }
    }
}