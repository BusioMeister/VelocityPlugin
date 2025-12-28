package ai.velocitysector;

import ai.velocitysector.redis.packet.Packet;

public final class GuildTagUpdatePacket implements Packet {

    public static final int ID = 10;

    public final String viewerUuid;
    public final String[] playerData;

    public GuildTagUpdatePacket(String viewerUuid, String[] playerData) {
        this.viewerUuid = viewerUuid;
        this.playerData = playerData;
    }

    @Override
    public int id() {
        return ID;
    }
}
