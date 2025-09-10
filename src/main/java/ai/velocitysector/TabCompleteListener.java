package ai.velocitysector;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.player.TabCompleteEvent;

public class TabCompleteListener {

    private final OnlinePlayersListener onlinePlayersListener;

    public TabCompleteListener(OnlinePlayersListener onlinePlayersListener) {
        this.onlinePlayersListener = onlinePlayersListener;
    }

    @Subscribe
    public void onTabComplete(TabCompleteEvent event) {
        String buffer = event.getPartialMessage();

        // Znajdź pozycję ostatniej spacji, aby wyodrębnić ostatnie słowo
        int lastSpaceIndex = buffer.lastIndexOf(' ');
        String currentArgument = buffer.substring(lastSpaceIndex + 1).toLowerCase();

        // Jeśli ostatnie słowo jest puste (np. po spacji), nie podpowiadaj
        if (currentArgument.isEmpty() && buffer.endsWith(" ")) {
            return;
        }

        for (String playerName : onlinePlayersListener.getAllOnlinePlayers()) {
            if (playerName.toLowerCase().startsWith(currentArgument)) {
                // Zapobiegaj dodawaniu duplikatów, jeśli już istnieje pełna sugestia
                if (!event.getSuggestions().contains(playerName)) {
                    event.getSuggestions().add(playerName);
                }
            }
        }
    }
}