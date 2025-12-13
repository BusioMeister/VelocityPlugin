package ai.velocitysector;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import net.kyori.adventure.text.Component;

import java.util.*;
import java.util.stream.Collectors;

public class MsgCommand implements SimpleCommand {

    private final ProxyServer proxy;
    private final OnlinePlayersListener onlinePlayersListener;
    private final Map<UUID, UUID> lastMessagerMap;

    // Usunięto RedisManager z konstruktora – nie jest już potrzebny
    public MsgCommand(ProxyServer proxy,
                      OnlinePlayersListener onlinePlayersListener,
                      Map<UUID, UUID> lastMessagerMap) {
        this.proxy = proxy;
        this.onlinePlayersListener = onlinePlayersListener;
        this.lastMessagerMap = lastMessagerMap;
    }

    @Override
    public void execute(Invocation invocation) {
        CommandSource sender = invocation.source();
        String[] args = invocation.arguments();

        // Permission na użycie komendy
        if (sender instanceof Player && !sender.hasPermission("aisector.msg.use")) {
            sender.sendMessage(Component.text("§cBrak uprawnień do użycia /msg."));
            return;
        }

        String senderName = (sender instanceof Player)
                ? ((Player) sender).getUsername()
                : "Konsola";

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

        // Bypass przez permisję lub konsolę
        boolean senderBypass = !(sender instanceof Player)
                || ((Player) sender).hasPermission("aisector.msg.bypass");

        // Blokada DM po stronie odbiorcy przez uprawnienie (LP)
        if (target.hasPermission("aisector.msg.block") && !senderBypass) {
            sender.sendMessage(Component.text("§cTen gracz ma wyłączone prywatne wiadomości."));
            return;
        }

        String message = String.join(" ", Arrays.copyOfRange(args, 1, args.length));

        // Wysłanie wiadomości do obu stron
        target.sendMessage(Component.text("§3" + senderName + "§b -> Ja §b" + message));
        sender.sendMessage(Component.text("§3 Ja -> " + target.getUsername() + " §b" + message));

        // Zapisanie pary konwersacji dla komendy /r
        if (sender instanceof Player) {
            Player senderPlayer = (Player) sender;
            lastMessagerMap.put(target.getUniqueId(), senderPlayer.getUniqueId());
            lastMessagerMap.put(senderPlayer.getUniqueId(), target.getUniqueId());
        }
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        if (invocation.arguments().length <= 1) {
            String partial = (invocation.arguments().length == 0)
                    ? "" : invocation.arguments()[0].toLowerCase();
            return onlinePlayersListener.getAllOnlinePlayers().stream()
                    .filter(name -> name.toLowerCase().startsWith(partial))
                    .collect(Collectors.toList());
        }
        return Collections.emptyList();
    }
}
