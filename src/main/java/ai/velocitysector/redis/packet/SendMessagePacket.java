package ai.velocitysector.redis.packet;

public class SendMessagePacket implements Packet {
    public String playerName;
    public String message;

    public SendMessagePacket() {}

    public SendMessagePacket(String playerName, String message) {
        this.playerName = playerName;
        this.message = message;
    }

    @Override
    public int id() {
        return 2;
    }
}
