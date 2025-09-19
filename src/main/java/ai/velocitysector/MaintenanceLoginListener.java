package ai.velocitysector;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.LoginEvent;
import net.kyori.adventure.text.Component;
import redis.clients.jedis.Jedis;

public class MaintenanceLoginListener {
    private final RedisManager redis;
    public MaintenanceLoginListener(RedisManager redis) { this.redis = redis; }

    @Subscribe
    public void onLogin(LoginEvent event) {
        boolean enabled;
        try (Jedis j = redis.getJedis()) { enabled = Boolean.TRUE.equals(j.exists("maintenance:enabled")); }
        catch (Exception e) { enabled = false; }
        if (!enabled) return;

        if (event.getPlayer().hasPermission("aisector.maintenance.bypass")) return;

        boolean allowed = false;
        try (Jedis j = redis.getJedis()) {
            allowed = Boolean.TRUE.equals(j.sismember("whitelist:players", event.getPlayer().getUniqueId().toString()));
        } catch (Exception ignored) {}

        if (!allowed) {
            event.setResult(LoginEvent.ComponentResult.denied(
                    Component.text("§cPrzerwa techniczna\n§7Whitelista jest włączona.")
            ));
        }
    }
}
