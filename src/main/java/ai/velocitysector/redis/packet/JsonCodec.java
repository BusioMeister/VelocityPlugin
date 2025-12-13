package ai.velocitysector.redis.packet;

import com.google.gson.Gson;

public class JsonCodec<T> {
    private static final Gson GSON = new Gson();
    private final Class<T> type;

    public JsonCodec(Class<T> type) {
        this.type = type;
    }

    public String encode(T obj) { return GSON.toJson(obj); }
    public T decode(String json) { return GSON.fromJson(json, type); }
}
