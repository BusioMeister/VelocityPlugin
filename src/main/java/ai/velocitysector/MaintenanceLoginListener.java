package ai.velocitysector;

import com.velocitypowered.api.event.EventTask;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.LoginEvent;
import net.kyori.adventure.text.Component;
import org.slf4j.Logger;
import java.util.concurrent.TimeUnit;

public final class MaintenanceLoginListener {
    private final RedisManager redis;
    private final Logger logger;

    public MaintenanceLoginListener(RedisManager redis, Logger logger) {
        this.redis = redis;
        this.logger = logger;
    }

    @Subscribe
    public EventTask onLogin(LoginEvent event) {
        if (event.getPlayer().hasPermission("aisector.maintenance.bypass")) {
            return EventTask.async(() -> { });
        }

        String uuid = event.getPlayer().getUniqueId().toString();
        return EventTask.withContinuation(continuation -> redis.executeAsync(jedis -> {
            if (!Boolean.TRUE.equals(jedis.exists("maintenance:enabled"))) return false;
            return !Boolean.TRUE.equals(jedis.sismember("whitelist:players", uuid));
        }).orTimeout(4, TimeUnit.SECONDS).whenComplete((deny, error) -> {
            if (error != null) {
                logger.warn("Could not verify maintenance whitelist during login; allowing connection: {}",
                        error.getMessage());
            } else if (Boolean.TRUE.equals(deny)) {
                event.setResult(LoginEvent.ComponentResult.denied(
                        Component.text("§cPrzerwa techniczna\n§7Whitelista jest włączona.")));
            }
            continuation.resume();
        }));
    }
}
