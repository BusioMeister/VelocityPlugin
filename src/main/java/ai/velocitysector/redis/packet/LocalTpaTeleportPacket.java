package ai.velocitysector.redis.packet;

public class LocalTpaTeleportPacket implements Packet {
    public String playerToTeleportName;
    public String world;
    public double x, y, z;
    public float yaw, pitch;
    public String message;

    @Override
    public int id() { return 4; }
}

