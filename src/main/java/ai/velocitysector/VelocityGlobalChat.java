package ai.velocitysector;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import org.slf4j.Logger;

public class VelocityGlobalChat {

    private final ProxyServer proxy;
    private final Logger logger;
    // Definiujemy stały, ujednolicony identyfikator kanału
    public static final MinecraftChannelIdentifier CHANNEL = MinecraftChannelIdentifier.create("global", "chat");

    public VelocityGlobalChat(ProxyServer proxy, Logger logger) {
        this.proxy = proxy;
        this.logger = logger;
    }

    @Subscribe
    public void onPluginMessage(PluginMessageEvent event) {
        // Sprawdzamy, czy wiadomość przyszła na nasz kanał
        if (!event.getIdentifier().equals(CHANNEL)) {
            return;
        }

        // Upewniamy się, że źródłem jest serwer gry
        if (!(event.getSource() instanceof ServerConnection)) {
            return;
        }

        // Pobieramy surowe dane wiadomości
        byte[] data = event.getData();

        // 🔥 KLUCZOWA POPRAWKA: Rozsyłamy wiadomość do WSZYSTKICH serwerów w sieci 🔥
        // Zamiast wysyłać do graczy, przekazujemy wiadomość dalej do serwerów.
        // Wtyczki na serwerach Bukkit (GlobalChatPlugin) odbiorą tę wiadomość
        // i wyświetlą ją na czacie.
        for (RegisteredServer server : proxy.getAllServers()) {
            server.sendPluginMessage(CHANNEL, data);
        }

        logger.info("Przekazano wiadomość z globalnego czatu do wszystkich serwerów.");
    }
}