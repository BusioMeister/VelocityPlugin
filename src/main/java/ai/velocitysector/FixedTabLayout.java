package ai.velocitysector;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import com.velocitypowered.api.proxy.player.TabListEntry;
import com.velocitypowered.api.util.GameProfile;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.List;
import java.util.UUID;

final class FixedTabLayout {
    static final MinecraftChannelIdentifier CHANNEL = MinecraftChannelIdentifier.from("aisector:tab_layout");
    private static final int SLOTS = 80;
    private static final List<GameProfile> PROFILES = java.util.stream.IntStream.range(0, SLOTS)
            .mapToObj(i -> new GameProfile(new UUID(0x4c617374506c6179L, i + 1L), "", List.of())).toList();

    @Subscribe
    public void onLayout(PluginMessageEvent event) {
        if (!CHANNEL.equals(event.getIdentifier())) return;
        event.setResult(PluginMessageEvent.ForwardResult.handled());
        if (!(event.getSource() instanceof ServerConnection source)) return;
        var player = source.getPlayer();
        if (player.getCurrentServer().orElse(null) != source
                || player.getProtocolVersion().getProtocol() != 777 || event.getData().length > 32767) return;
        Component[] rows = new Component[SLOTS];
        try (var input = new DataInputStream(new ByteArrayInputStream(event.getData()))) {
            if (input.readUnsignedByte() != 1) return;
            for (int i = 0; i < rows.length; i++) {
                rows[i] = GsonComponentSerializer.gson().deserialize(input.readUTF());
            }
            if (input.available() != 0) return;
        } catch (IOException | IllegalArgumentException invalid) {
            return;
        }
        var tab = player.getTabList();
        for (int i = 0; i < rows.length; i++) {
            var profile = PROFILES.get(i);
            var current = tab.getEntry(profile.getId()).orElse(null);
            if (current == null) {
                tab.addEntry(TabListEntry.builder().tabList(tab).profile(profile).displayName(rows[i])
                        .listOrder(Integer.MAX_VALUE - i).listed(true).showHat(false).latency(0).build());
            } else if (!rows[i].equals(current.getDisplayNameComponent().orElse(null))) {
                current.setDisplayName(rows[i]);
            }
        }
    }
}
