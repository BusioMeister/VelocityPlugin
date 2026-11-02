package ai.velocitysector;

import com.velocitypowered.api.proxy.ProxyServer;
import ai.aisector.shared.RedisChannels;
import com.velocitypowered.api.scheduler.ScheduledTask;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.slf4j.Logger;

import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import redis.clients.jedis.Jedis;

public final class MaintenanceManager {
    private final Object plugin;
    private final RedisManager redis;
    private final ProxyServer proxy;
    private final Logger logger;
    private volatile boolean maintenanceEnabled;
    private final AtomicLong stateVersion = new AtomicLong();
    private final AtomicLong countdownVersion = new AtomicLong();
    private volatile boolean closed;
    private CompletableFuture<Void> stateOperations = CompletableFuture.completedFuture(null);
    private ScheduledTask task;

    public MaintenanceManager(Object plugin, RedisManager redis, ProxyServer proxy, Logger logger) {
        this.plugin = plugin;
        this.redis = redis;
        this.proxy = proxy;
        this.logger = logger;
        long initialStateVersion = stateVersion.get();
        enqueueStateOperation(jedis -> Boolean.TRUE.equals(jedis.exists("maintenance:enabled")))
                .whenComplete((enabled, error) -> {
                    if (error != null) {
                        logger.warn("Could not load maintenance state from Redis", error);
                    } else if (stateVersion.get() == initialStateVersion) {
                        maintenanceEnabled = Boolean.TRUE.equals(enabled);
                    }
                });
    }

    public synchronized void startCountdown(String by, int seconds) {
        if (closed) return;
        cancel();
        long thisCountdown = countdownVersion.incrementAndGet();
        int total = Math.max(5, seconds);
        long start = System.currentTimeMillis();

        task = proxy.getScheduler().buildTask(plugin, () -> {
            if (countdownVersion.get() != thisCountdown) return;
            int left = Math.max(0, total - (int) ((System.currentTimeMillis() - start) / 1000));
            String title = "§cPrzerwa techniczna";
            String subtitle = "§7Start za §f" + left;
            proxy.getAllPlayers().forEach(player -> player.showTitle(Title.title(
                    Component.text(title),
                    Component.text(subtitle),
                    Title.Times.times(Duration.ofMillis(200), Duration.ofMillis(1000), Duration.ofMillis(200))
            )));

            if (left == 5) publish(RedisChannels.MAINTENANCE_PREPARE, "{}");
            if (left == 0) {
                cancel();
                enableMaintenanceAndKick(thisCountdown);
            }
        }).repeat(1L, TimeUnit.SECONDS).schedule();
    }

    public synchronized CompletableFuture<Boolean> disableMaintenance() {
        if (closed) return CompletableFuture.completedFuture(false);
        countdownVersion.incrementAndGet();
        cancel();
        long thisStateVersion = stateVersion.incrementAndGet();
        CompletableFuture<Long> write = enqueueStateOperation(jedis -> jedis.del("maintenance:enabled"));
        CompletableFuture<Boolean> completed = new CompletableFuture<>();
        write
                .whenComplete((ignored, error) -> {
                    if (error != null) {
                        logger.error("Could not disable maintenance in Redis", error);
                        completed.completeExceptionally(error);
                    } else if (stateVersion.get() == thisStateVersion) {
                        maintenanceEnabled = false;
                        completed.complete(true);
                    } else {
                        completed.complete(false);
                    }
                });
        return completed;
    }

    public boolean isMaintenanceEnabled() {
        return maintenanceEnabled;
    }

    private synchronized void enableMaintenanceAndKick(long thisCountdown) {
        if (closed || countdownVersion.get() != thisCountdown) return;
        long thisStateVersion = stateVersion.incrementAndGet();
        enqueueStateOperation(jedis -> {
            Set<String> entries = jedis.smembers("whitelist:players");
            jedis.set("maintenance:enabled", "1");
            return entries;
        }).whenComplete((entries, error) -> {
            if (error != null) {
                logger.error("Could not load the maintenance whitelist; players were not kicked", error);
                return;
            }
            if (closed || stateVersion.get() != thisStateVersion) return;
            maintenanceEnabled = true;

            Set<UUID> keep = new HashSet<>();
            for (String entry : entries) {
                try {
                    keep.add(UUID.fromString(entry));
                } catch (IllegalArgumentException ignored) {
                    logger.warn("Ignoring invalid UUID in maintenance whitelist: {}", entry);
                }
            }
            scheduleProxyTask(() -> {
                if (closed || stateVersion.get() != thisStateVersion) return;
                proxy.getAllPlayers().forEach(player -> {
                    if (!keep.contains(player.getUniqueId())
                            && !player.hasPermission("aisector.maintenance.bypass")) {
                        player.disconnect(Component.text("§cPrzerwa techniczna\n§7Whitelista jest włączona."));
                    }
                });
            });
        });
    }

    private void scheduleProxyTask(Runnable task) {
        try {
            proxy.getScheduler().buildTask(plugin, task).schedule();
        } catch (RuntimeException ignoredShutdown) {
            logger.debug("Skipping maintenance player action during proxy shutdown");
        }
    }

    public synchronized void close() {
        if (closed) return;
        closed = true;
        countdownVersion.incrementAndGet();
        stateVersion.incrementAndGet();
        cancel();
    }

    private synchronized void cancel() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    private synchronized <T> CompletableFuture<T> enqueueStateOperation(Function<Jedis, T> operation) {
        CompletableFuture<T> next = stateOperations.handle((ignored, error) -> null)
                .thenCompose(ignored -> redis.executeAsync(operation));
        stateOperations = next.handle((ignored, error) -> null);
        return next;
    }

    private void publish(String channel, String message) {
        redis.executeAsync(jedis -> jedis.publish(channel, message))
                .exceptionally(error -> {
                    logger.warn("Could not publish maintenance event {}", channel, error);
                    return null;
                });
    }
}
