package ai.aisector.shared.packet;

import ai.aisector.shared.Packet;

public final class LocalTpaTeleportPacket implements Packet {
    public static final int ID = 4;
    public String playerToTeleportName;
    public String world;
    public double x;
    public double y;
    public double z;
    public float yaw;
    public float pitch;
    public String message;

    public LocalTpaTeleportPacket() { }

    @Override
    public int id() { return ID; }
}
