package ai.velocitysector;

import com.velocitypowered.api.event.EventTask;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.LoginEvent;
import com.velocitypowered.api.proxy.Player;
import ai.aisector.shared.NetworkAddressUtil;
import net.kyori.adventure.text.Component;
import org.slf4j.Logger;
import redis.clients.jedis.Jedis;

import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

public final class BanLoginListener {
    private static final String CHECK_BAN_SCRIPT = "if redis.call('exists', KEYS[1]) == 0 then return nil; end; "
            + "local expires = redis.call('hget', KEYS[1], 'expires_at'); "
            + "local numericExpires = tonumber(expires); "
            + "if numericExpires and numericExpires ~= -1 and numericExpires < tonumber(ARGV[1]) then "
            + "redis.call('del', KEYS[1]); redis.call('srem', KEYS[2], ARGV[2]); return nil; end; "
            + "return {redis.call('hget', KEYS[1], 'reason') or '', "
            + "redis.call('hget', KEYS[1], 'banned_by') or '', expires or '-1'}";
    private final RedisManager redis;
    private final Logger logger;

    public BanLoginListener(RedisManager redis, Logger logger) {
        this.redis = redis;
        this.logger = logger;
    }

    @Subscribe
    public EventTask onLogin(LoginEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        String name = player.getUsername();
        String ip = Optional.ofNullable(player.getRemoteAddress())
                .filter(InetSocketAddress.class::isInstance)
                .map(InetSocketAddress.class::cast)
                .map(InetSocketAddress::getAddress)
                .map(address -> NetworkAddressUtil.normalizeIpLiteral(address.getHostAddress()).orElse(null))
                .orElse(null);

        return EventTask.withContinuation(continuation -> redis.executeAsync(jedis -> {
            // A new login must not inherit a transfer snapshot left behind by a crashed session.
            // Clearing it here, before Velocity admits the connection, avoids racing Paper's join handler.
            jedis.del("player:data:" + uuid);
            String userBan = activeBanMessage(jedis, "ban:user:" + uuid, "bans:users", uuid.toString(),
                    "§cZostałeś zbanowany!\n§7Nick: §f" + name);
            if (userBan != null) return userBan;
            return ip == null ? null : activeBanMessage(jedis, "ban:ip:" + ip, "bans:ips", ip,
                    "§cZostałeś zbanowany IP!");
        }).orTimeout(4, TimeUnit.SECONDS).whenComplete((message, error) -> {
            if (error != null) {
                logger.error("Could not check bans for {} during login", name, error);
                event.setResult(LoginEvent.ComponentResult.denied(Component.text(
                        "Nie można teraz zweryfikować bana. Spróbuj połączyć się ponownie za chwilę.")));
            } else if (message != null) {
                event.setResult(LoginEvent.ComponentResult.denied(Component.text(message)));
            }
            continuation.resume();
        }));
    }

    private String activeBanMessage(Jedis jedis, String key, String indexKey, String indexValue, String prefix) {
        Object result = jedis.eval(CHECK_BAN_SCRIPT, java.util.List.of(key, indexKey),
                java.util.List.of(Long.toString(System.currentTimeMillis()), indexValue));
        if (!(result instanceof java.util.List<?> fields) || fields.size() < 3) return null;
        String reason = String.valueOf(fields.get(0));
        String by = String.valueOf(fields.get(1));
        if (reason.length() > 256 || by.length() > 64) {
            logger.warn("Ban record {} contains oversized display fields; denying with a generic reason", key);
            reason = "Brak";
            by = "System";
        }
        long expiresAt;
        try {
            expiresAt = Long.parseLong(String.valueOf(fields.get(2)));
        } catch (NumberFormatException malformedExpiry) {
            // Keep malformed active records fail-closed until an administrator repairs them.
            expiresAt = -1;
        }
        String time = expiresAt == -1 ? "permanentnie" : "do " + Instant.ofEpochMilli(expiresAt);
        return prefix + "\n§7Powód: §f" + (reason.isEmpty() ? "Brak" : reason)
                + "\n§7Przez: §f" + (by.isEmpty() ? "System" : by) + "\n§7Czas: §f" + time;
    }
}
