package ai.velocitysector;

import com.velocitypowered.api.command.SimpleCommand;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public class GlobalPlayerTabCompleter implements SimpleCommand {

    private final OnlinePlayersListener onlinePlayersListener;

    public GlobalPlayerTabCompleter(OnlinePlayersListener onlinePlayersListener) {
        this.onlinePlayersListener = onlinePlayersListener;
    }

    @Override
    public void execute(Invocation invocation) {
        // Nie implementujemy w tej klasie, bo chodzi tylko o tabComplete
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        String[] args = invocation.arguments();

        if (args.length == 0 || args[0].isEmpty()) {
            return new ArrayList<>(onlinePlayersListener.getAllOnlinePlayers());
        }

        String partial = args[0].toLowerCase();

        Set<String> allPlayers = onlinePlayersListener.getAllOnlinePlayers();

        return allPlayers.stream()
                .filter(name -> name.toLowerCase().startsWith(partial))
                .collect(Collectors.toList());
    }
}
