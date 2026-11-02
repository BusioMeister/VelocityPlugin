package ai.aisector.shared;

import org.junit.jupiter.api.Test;
import ai.aisector.shared.packet.AlertPacket;
import ai.aisector.shared.packet.GuildTagUpdatePacket;
import ai.aisector.shared.packet.LocalTpaTeleportPacket;
import ai.aisector.shared.packet.SendMessagePacket;
import ai.aisector.shared.packet.TpaInitiateWarmupPacket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RedisChannelsTest {
    @Test
    void buildsPerServerChannelNames() {
        assertEquals("aisector:packet:Sector-1", RedisChannels.packetForServer("Sector-1"));
        assertEquals("aisector:maintenance_toggle", RedisChannels.MAINTENANCE_TOGGLE);
        assertEquals("aisector:maintenance_prepare", RedisChannels.MAINTENANCE_PREPARE);
        assertEquals("sector-online:Sector-1", RedisChannels.onlinePlayersForServer("Sector-1"));
    }

    @Test
    void rejectsInvalidServerNames() {
        assertThrows(IllegalArgumentException.class, () -> RedisChannels.packetForServer(""));
        assertThrows(IllegalArgumentException.class, () -> RedisChannels.onlinePlayersForServer("sector:extra"));
        assertThrows(IllegalArgumentException.class, () -> RedisChannels.packetForServer("x".repeat(65)));
        assertThrows(IllegalArgumentException.class, () -> RedisChannels.onlinePlayersForServer("bad\nname"));
    }

    @Test
    void packetIdsAreUniqueAndVersionedEnvelopeValidatesPayload() {
        int[] packetIds = {
                new TpaInitiateWarmupPacket().id(),
                new SendMessagePacket().id(),
                new AlertPacket().id(),
                new LocalTpaTeleportPacket().id(),
                new GuildTagUpdatePacket().id()
        };
        assertEquals(packetIds.length, java.util.Arrays.stream(packetIds).distinct().count());
        assertTrue(new PacketEnvelope(1, "{}").isValid());

        PacketEnvelope unsupportedVersion = new PacketEnvelope(1, "{}");
        unsupportedVersion.version++;
        assertFalse(unsupportedVersion.isValid());
        assertFalse(new PacketEnvelope(0, "{}").isValid());
        assertFalse(new PacketEnvelope(1, null).isValid());
        assertFalse(new PacketEnvelope(1, "").isValid());
        assertFalse(new PacketEnvelope(1, "x".repeat(PacketEnvelope.MAX_PAYLOAD_CHARS + 1)).isValid());
        assertTrue(new PacketEnvelope(1, "x".repeat(PacketEnvelope.MAX_PAYLOAD_CHARS)).isValid());
    }
}
