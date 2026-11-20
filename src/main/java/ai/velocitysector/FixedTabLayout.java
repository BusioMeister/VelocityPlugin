package ai.velocitysector;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;

final class FixedTabLayout {
    static final MinecraftChannelIdentifier CHANNEL = MinecraftChannelIdentifier.from("aisector:tab_panel");

    @Subscribe
    public void onPanel(PluginMessageEvent event) {
        if (!CHANNEL.equals(event.getIdentifier())) return;
        event.setResult(PluginMessageEvent.ForwardResult.handled());
        if (!(event.getSource() instanceof ServerConnection source)) return;
        var player = source.getPlayer();
        byte[] data = event.getData();
        if (player.getCurrentServer().orElse(null) != source
                || player.getProtocolVersion().getProtocol() != 777 || data.length != 1 || data[0] != 1) return;
        // Keep profiles available to the client for entities/skins and chat, but do not render
        // their rows. The backend draws the panel in the vanilla TAB header instead.
        for (var entry : player.getTabList().getEntries()) {
            if (entry.isListed()) entry.setListed(false);
        }
    }
}
