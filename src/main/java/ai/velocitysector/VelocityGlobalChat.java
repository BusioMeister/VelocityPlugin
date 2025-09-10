package ai.velocitysector;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import org.slf4j.Logger;

import javax.inject.Inject;

public class VelocityGlobalChat {

    private final ProxyServer proxy;
    private final Logger logger;
    private boolean channelActive = false;  // flaga, czy dostaliśmy choć raz wiadomość

    @Inject
    public VelocityGlobalChat(ProxyServer proxy, Logger logger) {
        this.proxy = proxy;
        this.logger = logger;
        logger.info("Plugin VelocityGlobalChat uruchomiony. Oczekuję na wiadomości na kanale 'global:chat'.");
    }

    @Subscribe
    public void onPluginMessage(PluginMessageEvent event) {
        String channel = event.getIdentifier().getId();

        logger.info("Odebrano plugin message na kanale: " + channel);

        if (!channel.equals("global:chat")) {
            return;
        }

        // Odbieramy tylko wiadomości od backendu (serwera Minecraft, np. Sector1, Sector2)
        if (!(event.getSource() instanceof ServerConnection)) {
            logger.info("Źródło wiadomości nie jest backendem (serwerem), ignoruję.");
            return;
        }

        if (!channelActive) {
            channelActive = true;
            logger.info("Kanał 'global:chat' jest aktywny i nasłuchiwany poprawnie.");
        }

        byte[] data = event.getData();

        // Opcjonalnie podgląd wiadomości
        try {
            String msg = new java.io.DataInputStream(new java.io.ByteArrayInputStream(data)).readUTF();
            logger.info("Treść wiadomości: " + msg);
            // Broadcast na wszystkie serwery (lub wybranych graczy)
            for (Player player : proxy.getAllPlayers()) {
                player.sendMessage(net.kyori.adventure.text.Component.text(msg));
            }
        } catch (Exception e) {
            logger.error("Błąd przy dekodowaniu wiadomości: " + e.getMessage());
        }

        event.setResult(PluginMessageEvent.ForwardResult.handled());
    }
}
