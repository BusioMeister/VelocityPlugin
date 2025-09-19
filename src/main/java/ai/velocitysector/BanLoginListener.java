package ai.velocitysector;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.LoginEvent;
import com.velocitypowered.api.proxy.ProxyServer;
import net.kyori.adventure.text.Component;
import org.slf4j.Logger;
import redis.clients.jedis.Jedis;

import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public class BanLoginListener {
    private final RedisManager redis;
    private final ProxyServer proxy;
    private final Logger logger;

    public BanLoginListener(RedisManager redis, ProxyServer proxy, Logger logger) {
        this.redis = redis; this.proxy = proxy; this.logger = logger;
    }

    @Subscribe
    public void onLogin(LoginEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        String name = event.getPlayer().getUsername();
        String ip = Optional.ofNullable(event.getPlayer().getRemoteAddress())
                .filter(a -> a instanceof InetSocketAddress)
                .map(a -> ((InetSocketAddress) a).getAddress().getHostAddress())
                .orElse("unknown");
        try (Jedis j = redis.getJedis()) {
            // Ban po UUID
            String uk = "ban:user:" + uuid;
            if (Boolean.TRUE.equals(j.exists(uk))) {
                long exp = Long.parseLong(j.hget(uk, "expires_at"));
                if (exp != -1 && exp < System.currentTimeMillis()) { j.del(uk); j.srem("bans:users", uuid.toString()); }
                else {
                    String reason = j.hget(uk, "reason");
                    String by = j.hget(uk, "banned_by");
                    String time = (exp == -1) ? "permanentnie" : ("do " + Instant.ofEpochMilli(exp));
                    Component msg = Component.text("§cZostałeś zbanowany!\n§7Nick: §f" + name + "\n§7Powód: §f" + reason + "\n§7Przez: §f" + by + "\n§7Czas: §f" + time);
                    event.setResult(LoginEvent.ComponentResult.denied(msg));
                    return;
                }
            }
            // Ban po IP
            String ik = "ban:ip:" + ip;
            if (Boolean.TRUE.equals(j.exists(ik))) {
                long exp = Long.parseLong(j.hget(ik, "expires_at"));
                if (exp != -1 && exp < System.currentTimeMillis()) { j.del(ik); j.srem("bans:ips", ip); }
                else {
                    String reason = j.hget(ik, "reason");
                    String by = j.hget(ik, "banned_by");
                    String time = (exp == -1) ? "permanentnie" : ("do " + Instant.ofEpochMilli(exp));
                    Component msg = Component.text("§cZostałeś zbanowany IP!\n§7Powód: §f" + reason + "\n§7Przez: §f" + by + "\n§7Czas: §f" + time);
                    event.setResult(LoginEvent.ComponentResult.denied(msg));
                }
            }
        }
    }
}
