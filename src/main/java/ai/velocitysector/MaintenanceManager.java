package ai.velocitysector;

import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.scheduler.ScheduledTask;
import net.kyori.adventure.title.Title;
import redis.clients.jedis.Jedis;

import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

public class MaintenanceManager {

    private final Object plugin;            // WŁAŚCICIEL zadań schedulera (np. instancja VelocityPlugin)
    private final RedisManager redis;
    private final ProxyServer proxy;
    private volatile boolean maintenanceEnabled = false;
    private ScheduledTask task;             // Używamy ScheduledTask zamiast ScheduledFuture

    public MaintenanceManager(Object plugin, RedisManager redis, ProxyServer proxy) {
        this.plugin = plugin;
        this.redis = redis;
        this.proxy = proxy;
        try (Jedis j = redis.getJedis()) {
            maintenanceEnabled = Boolean.TRUE.equals(j.exists("maintenance:enabled"));
        } catch (Exception ignored) {}
    }

    public void startCountdown(String by, int seconds) {
        cancel();
        final int total = Math.max(5, seconds);
        final long start = System.currentTimeMillis();

        // UWAGA: przekazujemy plugin jako owner do buildTask(...)
        task = proxy.getScheduler().buildTask(plugin, () -> {
            int left = total - (int)((System.currentTimeMillis() - start)/1000);
            if (left < 0) left = 0;

            String title = "§cPrzerwa techniczna";
            String sub = "§7Start za §f" + left ;
            proxy.getAllPlayers().forEach(p -> p.showTitle(Title.title(
                    net.kyori.adventure.text.Component.text(title),
                    net.kyori.adventure.text.Component.text(sub),
                    Title.Times.times(Duration.ofMillis(200), Duration.ofMillis(1000), Duration.ofMillis(200))
            )));

            if (left == 5) publish("aisector:maintenance_prepare", "{}");
            if (left == 0) {
                enableMaintenance();
                kickNonWhitelisted();
                cancel();
            }
        }).repeat(1L, TimeUnit.SECONDS).schedule();
    }

    public void enableMaintenance() {
        maintenanceEnabled = true;
        try (Jedis j = redis.getJedis()) { j.set("maintenance:enabled", "1"); } catch (Exception ignored) {}
    }

    public void disableMaintenance() {
        maintenanceEnabled = false;
        try (Jedis j = redis.getJedis()) { j.del("maintenance:enabled"); } catch (Exception ignored) {}
    }

    public boolean isMaintenanceEnabled() { return maintenanceEnabled; }

    private void kickNonWhitelisted() {
        Set<UUID> keep = new java.util.HashSet<>();
        try (Jedis j = redis.getJedis()) {
            for (String u : j.smembers("whitelist:players")) {
                try { keep.add(UUID.fromString(u)); } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
        proxy.getAllPlayers().forEach(p -> {
            if (!keep.contains(p.getUniqueId()) && !p.hasPermission("aisector.maintenance.bypass")) {
                p.disconnect(net.kyori.adventure.text.Component.text(
                        "§cPrzerwa techniczna\n§7Whitelista jest włączona."
                ));
            }
        });
    }

    private void cancel() {
        if (task != null) { task.cancel(); task = null; }  // cancel() bez argumentu
    }

    private void publish(String ch, String msg) {
        try (Jedis j = redis.getJedis()) { j.publish(ch, msg); } catch (Exception ignored) {}
    }
}
