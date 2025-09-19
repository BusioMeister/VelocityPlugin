package ai.velocitysector;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.player.TabCompleteEvent;
import java.util.Arrays;
import java.util.List;

public class TabCompleteListener {

    private final OnlinePlayersListener onlinePlayersListener;

    // --- POCZĄTEK POPRAWKI ---
    // Dodajemy "send" do listy komend
    private static final List<String> GLOBAL_PLAYER_COMMANDS = Arrays.asList(
            "sektor", "send", "s", "tp", "tpa", "invsee" ,"kick" ,"ban" ," banip", "unban"
    );
    // --- KONIEC POPRAWKI ---

    public TabCompleteListener(OnlinePlayersListener onlinePlayersListener) {
        this.onlinePlayersListener = onlinePlayersListener;
    }

    @Subscribe
    public void onTabComplete(TabCompleteEvent event) {
        String buffer = event.getPartialMessage();
        if (buffer.isEmpty() || !buffer.startsWith("/")) {
            return;
        }

        String noSlashBuffer = buffer.substring(1);
        String[] parts = noSlashBuffer.split(" ");
        String command = parts[0].toLowerCase();

        // Sprawdzamy, czy uzupełniamy pierwszy argument naszej komendy
        if (parts.length <= 2 && GLOBAL_PLAYER_COMMANDS.contains(command)) {
            String currentArgument = parts.length == 1 ? "" : parts[1].toLowerCase();

            onlinePlayersListener.getAllOnlinePlayers().stream()
                    .filter(name -> name.toLowerCase().startsWith(currentArgument))
                    .forEach(event.getSuggestions()::add);
        }
    }
}