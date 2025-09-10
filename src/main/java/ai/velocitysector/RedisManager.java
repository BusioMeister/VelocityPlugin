package ai.velocitysector;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.JedisPubSub;

import java.util.ArrayList;
import java.util.List;

public class RedisManager {

    private final JedisPool jedisPool;
    private final String host;
    private final int port;

    // Lista do śledzenia aktywnych subskrypcji, aby je poprawnie zamknąć
    private final List<JedisPubSub> activeSubscribers = new ArrayList<>();

    public RedisManager(String host, int port) {
        this.host = host;
        this.port = port;
        // Używamy standardowej, bezpiecznej konfiguracji puli
        this.jedisPool = new JedisPool(new JedisPoolConfig(), host, port);
    }

    // Ta metoda pozostaje bez zmian - dla zwykłych operacji
    public Jedis getJedis() {
        return jedisPool.getResource();
    }

    // 🔥 KLUCZOWA ZMIANA: Metoda 'subscribe' tworzy nowe, dedykowane połączenie
    public void subscribe(JedisPubSub listener, String... channels) {
        activeSubscribers.add(listener);
        new Thread(() -> {
            try (Jedis subscriberJedis = new Jedis(host, port)) {
                // Ta instancja 'subscriberJedis' jest używana TYLKO do nasłuchu
                subscriberJedis.subscribe(listener, channels);
            } catch (Exception e) {
                // Błąd jest oczekiwany, gdy serwer się zamyka i subskrypcja jest przerywana
                if (!e.getMessage().contains("Socket closed")) {
                    System.out.println("Błąd w wątku subskrypcji Redis: " + e.getMessage());
                }
            }
        }, "Redis-Subscriber-" + String.join("-", channels)).start();
    }

    // 🔥 KLUCZOWA ZMIANA: Metoda 'psubscribe' również tworzy nowe połączenie
    public void psubscribe(JedisPubSub listener, String pattern) {
        activeSubscribers.add(listener);
        new Thread(() -> {
            try (Jedis subscriberJedis = new Jedis(host, port)) {
                subscriberJedis.psubscribe(listener, pattern);
            } catch (Exception e) {
                if (!e.getMessage().contains("Socket closed")) {
                    System.out.println("Błąd w wątku subskrypcji Redis (pattern): " + e.getMessage());
                }
            }
        }, "Redis-Pattern-Subscriber-" + pattern).start();
    }

    public void close() {
        // Najpierw zamykamy wszystkie aktywne subskrypcje
        for (JedisPubSub subscriber : activeSubscribers) {
            try {
                if (subscriber.isSubscribed()) {
                    subscriber.unsubscribe();
                }
            } catch (Exception e) {
                // Ignoruj błędy, bo i tak zamykamy połączenie
            }
        }
        activeSubscribers.clear();

        // Potem zamykamy pulę połączeń
        if (jedisPool != null && !jedisPool.isClosed()) {
            jedisPool.close();
        }
    }
}