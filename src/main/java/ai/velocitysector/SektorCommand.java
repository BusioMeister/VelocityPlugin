package ai.velocitysector;

import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import java.util.List;
import java.util.stream.Collectors;

public class SektorCommand implements SimpleCommand {

    private final OnlinePlayersListener onlinePlayersListener;

    public SektorCommand(OnlinePlayersListener onlinePlayersListener) {
        this.onlinePlayersListener = onlinePlayersListener;
    }

    @Override
    public void execute(Invocation invocation) {
        if (invocation.arguments().length != 1) {
            invocation.source().sendMessage(Component.text("§cUżycie: /sektor <gracz>"));
            return;
        }

        String targetName = invocation.arguments()[0];
        String targetSector = onlinePlayersListener.getPlayerSector(targetName);

        if (targetSector != null) {
            invocation.source().sendMessage(Component.text("Gracz ").append(Component.text(targetName, NamedTextColor.YELLOW)).append(Component.text(" jest na sektorze ")).append(Component.text(targetSector, NamedTextColor.AQUA)).append(Component.text(".")));
        } else {
            invocation.source().sendMessage(Component.text("§cGracz o nicku '" + targetName + "' nie jest online w sieci."));
        }
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        // Używamy naszego istniejącego TabCompletera dla spójności
        if (invocation.arguments().length <= 1) {
            String partial = (invocation.arguments().length == 0) ? "" : invocation.arguments()[0].toLowerCase();
            return onlinePlayersListener.getAllOnlinePlayers().stream()
                    .filter(name -> name.toLowerCase().startsWith(partial))
                    .collect(Collectors.toList());
        }
        return List.of();
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        return invocation.source().hasPermission("aisector.velocity.sektor");
    }
}