package ai.velocitysector;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.player.TabCompleteEvent;
import com.velocitypowered.api.proxy.Player;

import java.util.Arrays;
import java.util.List;

public class TabCompleteListener {

    private final OnlinePlayersListener onlinePlayersListener;

    // 🔥 KROK 1: Dodajemy listę komend na początku klasy 🔥
    private static final List<String> GLOBAL_PLAYER_COMMANDS = Arrays.asList(
            "sektor", "send", "s", "tp", "tpa", "invsee"
    );

    public TabCompleteListener(OnlinePlayersListener onlinePlayersListener) {
        this.onlinePlayersListener = onlinePlayersListener;
    }

    @Subscribe
    public void onTabComplete(TabCompleteEvent event) {
        String buffer = event.getPartialMessage();

        // Jeśli bufor jest pusty, nie rób nic
        if (buffer.isEmpty()) {
            return;
        }

        // Usuwamy / z początku, jeśli istnieje
        String noSlashBuffer = buffer.startsWith("/") ? buffer.substring(1) : buffer;
        String[] parts = noSlashBuffer.split(" ");
        String command = parts[0].toLowerCase();

        // 🔥 KROK 2: Sprawdzamy, czy komenda jest na naszej liście i czy uzupełniamy pierwszy argument 🔥
        if (parts.length > 1 && GLOBAL_PLAYER_COMMANDS.contains(command)) {
            // Uzupełniamy tylko ostatni argument
            String currentArgument = parts[parts.length - 1].toLowerCase();

            // Jeśli ostatni argument jest pusty (czyli wpisano spację), podpowiedz wszystkich graczy
            if (currentArgument.isEmpty() && buffer.endsWith(" ")) {
                event.getSuggestions().addAll(onlinePlayersListener.getAllOnlinePlayers());
                return;
            }

            // Jeśli gracz zaczął coś pisać, filtrujemy nicki
            for (String playerName : onlinePlayersListener.getAllOnlinePlayers()) {
                if (playerName.toLowerCase().startsWith(currentArgument)) {
                    event.getSuggestions().add(playerName);
                }
            }
        }
    }
}