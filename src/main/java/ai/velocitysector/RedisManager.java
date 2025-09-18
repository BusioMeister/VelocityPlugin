// RedisManager.java (Velocity)
package ai.velocitysector;

import redis.clients.jedis.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

public class RedisManager {

    private final JedisPool jedisPool;
    private final String host;
    private final int port;
    private final String password; // null jeśli brak
    private final List<JedisPubSub> activeSubscribers = new CopyOnWriteArrayList<>();

    public RedisManager(String host, int port) {
        this(host, port, null);
    }

    public RedisManager(String host, int port, String password) {
        this.host = Objects.requireNonNull(host, "host");
        this.port = port;
        this.password = password;

        JedisPoolConfig cfg = new JedisPoolConfig();
        cfg.setMaxTotal(16);
        cfg.setMaxIdle(8);
        cfg.setMinIdle(1);
        cfg.setTestOnBorrow(true);
        cfg.setTestWhileIdle(true);

        if (password == null || password.isEmpty()) {
            this.jedisPool = new JedisPool(cfg, host, port, 2000);
        } else {
            this.jedisPool = new JedisPool(cfg, host, port, 2000, password);
        }
    }

    public Jedis getJedis() {
        return jedisPool.getResource();
    }

    public void subscribe(JedisPubSub listener, String... channels) {
        Objects.requireNonNull(listener, "listener");
        activeSubscribers.add(listener);
        Thread t = new Thread(() -> {
            try (Jedis sub = new Jedis(host, port)) {
                if (password != null && !password.isEmpty()) sub.auth(password);
                sub.subscribe(listener, channels);
            } catch (Exception e) {
                if (e.getMessage() == null || !e.getMessage().contains("Socket closed")) {
                    System.out.println("Błąd subskrypcji Redis: " + e.getMessage());
                }
            }
        }, "Redis-Subscriber-" + String.join("-", channels));
        t.setDaemon(true);
        t.start();
    }

    public void psubscribe(JedisPubSub listener, String pattern) {
        Objects.requireNonNull(listener, "listener");
        activeSubscribers.add(listener);
        Thread t = new Thread(() -> {
            try (Jedis sub = new Jedis(host, port)) {
                if (password != null && !password.isEmpty()) sub.auth(password);
                sub.psubscribe(listener, pattern);
            } catch (Exception e) {
                if (e.getMessage() == null || !e.getMessage().contains("Socket closed")) {
                    System.out.println("Błąd psubskrypcji Redis: " + e.getMessage());
                }
            }
        }, "Redis-Pattern-Subscriber-" + pattern);
        t.setDaemon(true);
        t.start();
    }

    public void close() {
        for (JedisPubSub s : activeSubscribers) {
            try {
                if (s.isSubscribed()) s.unsubscribe();
            } catch (Exception ignore) {}
        }
        activeSubscribers.clear();
        if (jedisPool != null && !jedisPool.isClosed()) jedisPool.close();
    }
}
