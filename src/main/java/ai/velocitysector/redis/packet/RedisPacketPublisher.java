package ai.velocitysector.redis.packet;

import ai.aisector.shared.PacketEnvelope;
import ai.aisector.shared.Packet;
import com.google.gson.Gson;
import redis.clients.jedis.Jedis;

public class RedisPacketPublisher {
    private static final Gson GSON = new Gson();

    public long publish(Jedis jedis, String channel, Packet packet, String payloadJson) {
        PacketEnvelope env = new PacketEnvelope(packet.id(), payloadJson);
        return jedis.publish(channel, GSON.toJson(env));
    }
}
