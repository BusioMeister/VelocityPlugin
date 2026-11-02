package ai.aisector.shared;

/** Versioned, platform-neutral wire envelope used for Redis Pub/Sub packets. */
public final class PacketEnvelope {
    public static final int CURRENT_VERSION = 1;
    public static final int MAX_PAYLOAD_CHARS = 1_000_000;

    public int id;
    public int version = CURRENT_VERSION;
    public String payload;

    public PacketEnvelope() {
    }

    public PacketEnvelope(int id, String payload) {
        this.id = id;
        this.payload = payload;
    }

    public boolean isValid() {
        return version == CURRENT_VERSION && id > 0 && payload != null
                && !payload.isEmpty() && payload.length() <= MAX_PAYLOAD_CHARS;
    }
}
