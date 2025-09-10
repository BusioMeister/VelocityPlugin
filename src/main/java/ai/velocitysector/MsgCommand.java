package ai.velocitysector;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import net.kyori.adventure.text.Component;
import redis.clients.jedis.Jedis;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

public class MsgCommand implements SimpleCommand {

    private final ProxyServer proxy;
    private final OnlinePlayersListener onlinePlayersListener;
    private final RedisManager redisManager;
    private final Map<UUID, UUID> lastMessagerMap;

    public MsgCommand(ProxyServer proxy, OnlinePlayersListener onlinePlayersListener, RedisManager redisManager, Map<UUID, UUID> lastMessagerMap) {
        this.proxy = proxy;
        this.onlinePlayersListener = onlinePlayersListener;
        this.redisManager = redisManager;
        this.lastMessagerMap = lastMessagerMap;
    }

    @Override
    public void execute(Invocation invocation) {
        CommandSource sender = invocation.source();
        String[] args = invocation.arguments();

        String senderName;
        if (sender instanceof Player) {
            senderName = ((Player) sender).getUsername();
        } else {
            senderName = "Konsola";
        }

        if (args.length < 2) {
            sender.sendMessage(Component.text("§cUżycie: /msg <gracz> <wiadomość>"));
            return;
        }

        String targetName = args[0];
        Player target = proxy.getPlayer(targetName).orElse(null);

        if (target == null) {
            sender.sendMessage(Component.text("§cGracz " + targetName + " nie jest online."));
            return;
        }

        // Sprawdzenie, czy odbiorca ma wyłączone prywatne wiadomości
        try (Jedis jedis = redisManager.getJedis()) {
            if (jedis.exists("pm_disabled:" + target.getUniqueId())) {
                sender.sendMessage(Component.text("§cTen gracz ma wyłączone prywatne wiadomości."));
                return;
            }
        }

        String message = String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length));

        // Wysłanie wiadomości do obu stron
        target.sendMessage(Component.text("§b[PW od §b" + senderName + "§b] §b" + message));
        sender.sendMessage(Component.text("§b[PW do §b" + target.getUsername() + "§b] §b" + message));

        // Zapisanie pary konwersacji dla komendy /r
        if (sender instanceof Player) {
            Player senderPlayer = (Player) sender;
            // Zapisujemy w obie strony, aby /r działało dla obu graczy
            lastMessagerMap.put(target.getUniqueId(), senderPlayer.getUniqueId());
            lastMessagerMap.put(senderPlayer.getUniqueId(), target.getUniqueId());
        }
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        if (invocation.arguments().length <= 1) {
            String partial = (invocation.arguments().length == 0) ? "" : invocation.arguments()[0].toLowerCase();
            return onlinePlayersListener.getAllOnlinePlayers().stream()
                    .filter(name -> name.toLowerCase().startsWith(partial))
                    .collect(Collectors.toList());
        }
        return List.of();
    }
}