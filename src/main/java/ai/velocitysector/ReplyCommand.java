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
            invocation.source().sendMessage(Component.text(Messages.text("reply-command.tej-komendy-moze-uzyc-tylko-gracz")));
            return;
        }

        Player sender = (Player) invocation.source();
        String[] args = invocation.arguments();

        if (args.length == 0) {
            sender.sendMessage(Component.text(Messages.text("reply-command.uzycie-r")));
            return;
        }

        // Sprawdź, do kogo gracz ma odpowiedzieć
        UUID targetUuid = lastMessagerMap.get(sender.getUniqueId());
        if (targetUuid == null) {
            sender.sendMessage(Component.text(Messages.text("reply-command.nie-masz-komu-odpowiedziec")));
            return;
        }

        Optional<Player> targetOptional = proxy.getPlayer(targetUuid);
        if (!targetOptional.isPresent()) {
            sender.sendMessage(Component.text(Messages.text("reply-command.gracz-ktoremu-probujesz-odpowiedziec-jest-offline")));
            return;
        }
        Player target = targetOptional.get();

        String message = String.join(" ", args);
        if (message.isBlank() || message.length() > 2_048) {
            sender.sendMessage(Component.text(Messages.text("reply-command.wiadomosc-musi-miec-od-1-do-2048")));
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
                sender.sendMessage(Component.text(Messages.text("reply-command.nie-udalo-sie-sprawdzic-ustawien-prywatnych-wiadomosci")));
                return;
            }
            if (Boolean.TRUE.equals(targetDisabled)) {
                sender.sendMessage(Component.text(Messages.text("reply-command.ten-gracz-ma-wylaczone-prywatne-wiadomosci")));
                return;
            }
            Player currentTarget = proxy.getPlayer(targetUuid).orElse(null);
            if (currentTarget == null || currentTarget != target) {
                sender.sendMessage(Component.text(Messages.text("reply-command.gracz-ktoremu-probujesz-odpowiedziec-jest-offline")));
                return;
            }
            currentTarget.sendMessage(Component.text(Messages.text("reply-command.ja", sender.getUsername(), message)));
            currentSender.sendMessage(Component.text(Messages.text("reply-command.ja-2", currentTarget.getUsername(), message)));
        });
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        // Komenda dostępna dla wszystkich graczy
        return true;
    }
}
