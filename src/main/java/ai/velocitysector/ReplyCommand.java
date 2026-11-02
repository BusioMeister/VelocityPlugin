package ai.velocitysector;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import net.kyori.adventure.text.Component;
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

        String message = String.join(" ", args);
        if (message.isBlank() || message.length() > 2_048) {
            sender.sendMessage(Component.text("§cWiadomość musi mieć od 1 do 2048 znaków."));
            return;
        }
        UUID senderUuid = sender.getUniqueId();
        boolean permissionBypass = sender.hasPermission("aisector.wyjebane.bypass");
        redisManager.executeAsync(jedis -> {
            boolean senderBypass = permissionBypass || jedis.exists("bypass_pm:" + sender.getUniqueId());
            boolean targetDisabled = jedis.exists("pm_disabled:" + target.getUniqueId());
            return targetDisabled && !senderBypass;
        }).whenComplete((targetDisabled, error) -> {
            Player currentSender = proxy.getPlayer(senderUuid).orElse(null);
            if (currentSender != sender) return;
            if (error != null) {
                sender.sendMessage(Component.text("§cNie udało się sprawdzić ustawień prywatnych wiadomości."));
                return;
            }
            if (Boolean.TRUE.equals(targetDisabled)) {
                sender.sendMessage(Component.text("§cTen gracz ma wyłączone prywatne wiadomości."));
                return;
            }
            Player currentTarget = proxy.getPlayer(targetUuid).orElse(null);
            if (currentTarget == null || currentTarget != target) {
                sender.sendMessage(Component.text("§cGracz, któremu próbujesz odpowiedzieć, jest offline."));
                return;
            }
            currentTarget.sendMessage(Component.text("§3" + sender.getUsername() + "§b -> Ja §b" + message));
            currentSender.sendMessage(Component.text("§3 Ja -> " + currentTarget.getUsername() + " §b" + message));
        });
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        // Komenda dostępna dla wszystkich graczy
        return true;
    }
}
