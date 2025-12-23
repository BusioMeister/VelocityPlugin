package ai.velocitysector.redis.packet;

public class AlertPacket implements Packet {
    public String message;

    public AlertPacket() {}

    public AlertPacket(String message) {
        this.message = message;
    }

    @Override
    public int id() {
        return 3;
    }
}
