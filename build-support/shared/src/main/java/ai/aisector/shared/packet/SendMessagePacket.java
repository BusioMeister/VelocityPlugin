package ai.aisector.shared.packet;

import ai.aisector.shared.Packet;

public final class SendMessagePacket implements Packet {
    public static final int ID = 2;
    public String playerName;
    public String message;

    public SendMessagePacket() { }

    public SendMessagePacket(String playerName, String message) {
        this.playerName = playerName;
        this.message = message;
    }

    @Override
    public int id() { return ID; }
}
