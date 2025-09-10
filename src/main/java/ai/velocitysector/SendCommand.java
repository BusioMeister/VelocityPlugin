package ai.velocitysector;

import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import net.kyori.adventure.text.Component;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

public class SendCommand implements SimpleCommand {

    private final ProxyServer proxy;

    private final OnlinePlayersListener onlinePlayersListener;

    public SendCommand(ProxyServer proxy, OnlinePlayersListener onlinePlayersListener) {
        this.proxy = proxy;
        this.onlinePlayersListener = onlinePlayersListener;
    }

    @Override
    public void execute(Invocation invocation) {
        if (invocation.arguments().length != 2) {
            invocation.source().sendMessage(Component.text("§cUżycie: /send <gracz> <sektor>"));
            return;
        }

        String targetName = invocation.arguments()[0];
        String sectorName = invocation.arguments()[1];

        Optional<Player> target = proxy.getPlayer(targetName);
        if (!target.isPresent()) {
            invocation.source().sendMessage(Component.text("§cGracz o nicku '" + targetName + "' nie jest online."));
            return;
        }

        Optional<RegisteredServer> server = proxy.getServer(sectorName);
        if (!server.isPresent()) {
            invocation.source().sendMessage(Component.text("§cSerwer o nazwie '" + sectorName + "' nie istnieje."));
            return;
        }

        target.get().createConnectionRequest(server.get()).fireAndForget();
        invocation.source().sendMessage(Component.text("§aWysłano gracza " + targetName + " na serwer " + sectorName + "."));
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        return invocation.source().hasPermission("aisector.velocity.send");
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        String[] args = invocation.arguments();
        // Uzupełnianie nicków dla pierwszego argumentu
        if (args.length <= 1) {
            String partial = (args.length == 0) ? "" : args[0].toLowerCase();
            // 🔥 ZMIANA: Używamy teraz spójnej, globalnej listy graczy
            return onlinePlayersListener.getAllOnlinePlayers().stream()
                    .filter(name -> name.toLowerCase().startsWith(partial))
                    .collect(Collectors.toList());
        }
        // Uzupełnianie nazw serwerów dla drugiego argumentu
        if (args.length == 2) {
            String partial = args[1].toLowerCase();
            return proxy.getAllServers().stream()
                    .map(s -> s.getServerInfo().getName())
                    .filter(name -> name.toLowerCase().startsWith(partial))
                    .collect(Collectors.toList());
        }
        return List.of();
    }
}