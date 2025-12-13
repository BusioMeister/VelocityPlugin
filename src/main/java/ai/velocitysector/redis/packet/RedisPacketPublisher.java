package ai.velocitysector.redis.packet;

import com.google.gson.Gson;
import redis.clients.jedis.Jedis;

public class RedisPacketPublisher {
    private static final Gson GSON = new Gson();

    public void publish(Jedis jedis, String channel, Packet packet, String payloadJson) {
        PacketEnvelope env = new PacketEnvelope();
        env.id = packet.id();
        env.payload = payloadJson;
        jedis.publish(channel, GSON.toJson(env));
    }
}
