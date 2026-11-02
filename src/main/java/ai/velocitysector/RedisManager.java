package ai.velocitysector;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.JedisPubSub;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.function.Consumer;

/** Redis command pool and reconnecting dedicated Pub/Sub connections for Velocity. */
public final class RedisManager implements AutoCloseable {
    private final JedisPool jedisPool;
    private final String host;
    private final int port;
    private final String password;
    private final Consumer<String> errorLogger;
    private final List<JedisPubSub> activeSubscribers = new CopyOnWriteArrayList<>();
    private final java.util.Set<JedisPubSub> patternSubscribers = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final java.util.Map<Thread, Jedis> subscriptionConnections = new java.util.concurrent.ConcurrentHashMap<>();
    private final List<Thread> subscriptionThreads = new CopyOnWriteArrayList<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final ExecutorService commandExecutor = new ThreadPoolExecutor(2, 2, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(4096), runnable -> {
                Thread thread = new Thread(runnable, "aiSector-velocity-redis-command");
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());

    public RedisManager(String host, int port, String password, Consumer<String> errorLogger) {
        this.host = Objects.requireNonNull(host, "host");
        if (port < 1 || port > 65_535) {
            throw new IllegalArgumentException("Redis port must be between 1 and 65535");
        }
        this.port = port;
        this.password = password == null ? "" : password;
        this.errorLogger = Objects.requireNonNull(errorLogger, "errorLogger");

        JedisPoolConfig config = new JedisPoolConfig();
        config.setMaxTotal(32);
        config.setMaxIdle(16);
        config.setMinIdle(1);
        config.setTestWhileIdle(true);
        this.jedisPool = this.password.isEmpty()
                ? new JedisPool(config, host, port, 2_000)
                : new JedisPool(config, host, port, 2_000, this.password);
    }

    public Jedis getJedis() {
        return jedisPool.getResource();
    }

    public CompletableFuture<Long> deleteAsync(String... keys) {
        return executeAsync(jedis -> jedis.del(keys));
    }

    public <T> CompletableFuture<T> executeAsync(Function<Jedis, T> command) {
        Objects.requireNonNull(command, "command");
        if (closed.get()) {
            return CompletableFuture.failedFuture(new IllegalStateException("RedisManager is closed"));
        }
        try {
            return CompletableFuture.supplyAsync(() -> {
                try (Jedis jedis = getJedis()) {
                    return command.apply(jedis);
                }
            }, commandExecutor);
        } catch (RejectedExecutionException exception) {
            return CompletableFuture.failedFuture(exception);
        }
    }

    public void subscribe(JedisPubSub listener, String... channels) {
        startSubscription(listener, false, channels);
    }

    public void psubscribe(JedisPubSub listener, String... patterns) {
        startSubscription(listener, true, patterns);
    }

    private void startSubscription(JedisPubSub listener, boolean pattern, String... channels) {
        Objects.requireNonNull(listener, "listener");
        if (closed.get()) {
            throw new IllegalStateException("RedisManager is closed");
        }
        activeSubscribers.add(listener);
        if (pattern) patternSubscribers.add(listener);
        Thread thread = new Thread(() -> {
            long retryDelayMillis = 1_000L;
            try {
                while (!closed.get() && !Thread.currentThread().isInterrupted()) {
                    try (Jedis jedis = new Jedis(host, port)) {
                        subscriptionConnections.put(Thread.currentThread(), jedis);
                        if (closed.get() || Thread.currentThread().isInterrupted()) break;
                        if (!password.isEmpty()) {
                            jedis.auth(password);
                        }
                        if (pattern) {
                            jedis.psubscribe(listener, channels);
                        } else {
                            jedis.subscribe(listener, channels);
                        }
                        retryDelayMillis = 1_000L;
                    } catch (Exception exception) {
                        if (!closed.get()) {
                            errorLogger.accept("Redis subscription failed for " + String.join(",", channels)
                                    + "; retrying: " + exception.getMessage());
                        }
                    } finally { subscriptionConnections.remove(Thread.currentThread()); }
                    if (!closed.get()) {
                        Thread.sleep(retryDelayMillis);
                        retryDelayMillis = Math.min(retryDelayMillis * 2, 30_000L);
                    }
                }
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            } finally {
                activeSubscribers.remove(listener);
                patternSubscribers.remove(listener);
                subscriptionThreads.remove(Thread.currentThread());
            }
        }, "aiSector-velocity-redis-subscription-" + Integer.toHexString(System.identityHashCode(listener)));
        subscriptionThreads.add(thread);
        thread.setDaemon(true);
        thread.start();
        if (closed.get()) thread.interrupt();
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        for (JedisPubSub subscriber : activeSubscribers) {
            try {
                if (patternSubscribers.contains(subscriber)) subscriber.punsubscribe();
                else subscriber.unsubscribe();
            } catch (RuntimeException ignored) {
                // The subscriber may already be disconnected.
            }
        }
        activeSubscribers.clear();
        List<Thread> subscribers = List.copyOf(subscriptionThreads);
        subscribers.forEach(Thread::interrupt);
        subscriptionConnections.values().forEach(connection -> {
            try { connection.disconnect(); } catch (RuntimeException ignored) { }
        });
        for (Thread subscriberThread : subscribers) {
            try {
                subscriberThread.join(2_500L);
                if (subscriberThread.isAlive()) {
                    errorLogger.accept("Timed out waiting for Redis subscription thread to stop: "
                            + subscriberThread.getName());
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                errorLogger.accept("Interrupted while stopping Redis subscription threads.");
                break;
            }
        }
        commandExecutor.shutdown();
        try {
            if (!commandExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                errorLogger.accept("Timed out waiting for queued Redis commands during shutdown; interrupting remaining tasks.");
                commandExecutor.shutdownNow();
                commandExecutor.awaitTermination(1, TimeUnit.SECONDS);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            commandExecutor.shutdownNow();
        }
        jedisPool.close();
    }
}
