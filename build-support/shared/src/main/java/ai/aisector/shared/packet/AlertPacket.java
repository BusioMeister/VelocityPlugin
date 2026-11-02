package ai.aisector.shared.packet;

import ai.aisector.shared.Packet;

public final class AlertPacket implements Packet {
    public static final int ID = 3;
    public String message;

    public AlertPacket() { }

    @Override
    public int id() { return ID; }
}
