package ai.velocitysector.redis.packet;

public class TpaInitiateWarmupPacket implements Packet {
    public String requesterName;
    public String targetServerName;

    public String world;
    public double x, y, z;
    public float yaw, pitch;

    public TpaInitiateWarmupPacket() {}

    @Override
    public int id() {
        return 1;
    }
}
