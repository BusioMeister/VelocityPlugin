package ai.velocitysector;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.JedisPubSub;

public class RedisManager {

    private final JedisPool jedisPool;

    public RedisManager(String host, int port) {
        this.jedisPool = new JedisPool(new JedisPoolConfig(), host, port);
    }

    // Pobieranie połączenia do zwykłych operacji (GET, SET, DEL, etc.)
    public Jedis getJedis() {
        return jedisPool.getResource();
    }

    // Dedykowane połączenie dla Pub/Sub
    public void subscribe(JedisPubSub listener, String channel) {
        new Thread(() -> {
            try (Jedis jedis = jedisPool.getResource()) {
                jedis.subscribe(listener, channel);
            } catch (Exception e) {
                e.printStackTrace();
            }

        }).start();
    }
    public void psubscribe(JedisPubSub listener, String pattern) {
        new Thread(() -> {
            try (Jedis jedis = jedisPool.getResource()) {
                jedis.psubscribe(listener, pattern);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }).start();
    }



    // Zamknięcie puli połączeń
    public void close() {
        jedisPool.close();
    }
}