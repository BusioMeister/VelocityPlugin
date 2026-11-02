package ai.aisector.shared.packet;

import ai.aisector.shared.Packet;

public final class TpaInitiateWarmupPacket implements Packet {
    public static final int ID = 1;
    public String requesterName;
    public String targetServerName;
    public String world;
    public double x;
    public double y;
    public double z;
    public float yaw;
    public float pitch;

    public TpaInitiateWarmupPacket() { }

    public TpaInitiateWarmupPacket(String requesterName, String targetServerName,
                                   String world, double x, double y, double z, float yaw, float pitch) {
        this.requesterName = requesterName;
        this.targetServerName = targetServerName;
        this.world = world;
        this.x = x;
        this.y = y;
        this.z = z;
        this.yaw = yaw;
        this.pitch = pitch;
    }

    @Override
    public int id() { return ID; }
}
