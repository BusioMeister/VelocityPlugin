package ai.velocitysector;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import net.kyori.adventure.text.Component;
import redis.clients.jedis.Jedis;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public class ReplyCommand implements SimpleCommand {

    private final ProxyServer proxy;
    private final RedisManager redisManager;
    private final Map<UUID, UUID> lastMessagerMap;

    public ReplyCommand(ProxyServer proxy, RedisManager redisManager, Map<UUID, UUID> lastMessagerMap) {
        this.proxy = proxy;
        this.redisManager = redisManager;
        this.lastMessagerMap = lastMessagerMap;
    }

    @Override
    public void execute(Invocation invocation) {
        if (!(invocation.source() instanceof Player)) {
            invocation.source().sendMessage(Component.text("Tej komendy może użyć tylko gracz."));
            return;
        }

        Player sender = (Player) invocation.source();
        String[] args = invocation.arguments();

        if (args.length == 0) {
            sender.sendMessage(Component.text("§cUżycie: /r <wiadomość>"));
            return;
        }

        // Sprawdź, do kogo gracz ma odpowiedzieć
        UUID targetUuid = lastMessagerMap.get(sender.getUniqueId());
        if (targetUuid == null) {
            sender.sendMessage(Component.text("§cNie masz komu odpowiedzieć."));
            return;
        }

        Optional<Player> targetOptional = proxy.getPlayer(targetUuid);
        if (!targetOptional.isPresent()) {
            sender.sendMessage(Component.text("§cGracz, któremu próbujesz odpowiedzieć, jest offline."));
            return;
        }
        Player target = targetOptional.get();

        // ReplyCommand.execute — przed sprawdzeniem targetDisabled
        boolean senderBypass = sender.hasPermission("aisector.wyjebane.bypass");
        if (!senderBypass) {
            try (redis.clients.jedis.Jedis j = redisManager.getJedis()) {
                senderBypass = j.exists("bypass_pm:" + sender.getUniqueId());
            }
        }

        try (redis.clients.jedis.Jedis j = redisManager.getJedis()) {
            boolean targetDisabled = j.exists("pm_disabled:" + target.getUniqueId());
            if (targetDisabled && !senderBypass) {
                sender.sendMessage(Component.text("§cTen gracz ma wyłączone prywatne wiadomości."));
                return;
            }
        }


        String message = String.join(" ", args);
        target.sendMessage(Component.text("§3" + sender.getUsername() + "§b -> Ja §b" + message));
        sender.sendMessage(Component.text("§3 Ja -> " + target.getUsername() + " §b" + message));
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        // Komenda dostępna dla wszystkich graczy
        return true;
    }
}