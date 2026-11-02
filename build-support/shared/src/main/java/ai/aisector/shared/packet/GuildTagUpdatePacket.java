package ai.aisector.shared.packet;

import ai.aisector.shared.Packet;

public final class GuildTagUpdatePacket implements Packet {
    public static final int ID = 10;
    public String viewerUuid;
    public String[] playerData;

    public GuildTagUpdatePacket() { }

    public GuildTagUpdatePacket(String viewerUuid, String[] playerData) {
        this.viewerUuid = viewerUuid;
        this.playerData = playerData;
    }

    @Override
    public int id() { return ID; }
}
