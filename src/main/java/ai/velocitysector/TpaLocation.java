package ai.velocitysector;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

/** Validated coordinates carried by a cross-server TPA acceptance message. */
record TpaLocation(String world, double x, double y, double z, float yaw, float pitch) {
    private static final double MAX_HORIZONTAL_COORDINATE = 30_000_000.0;
    private static final double MAX_VERTICAL_COORDINATE = 4_096.0;

    static TpaLocation parse(JsonObject json) {
        if (json == null) throw new IllegalArgumentException("Missing TPA location");
        String world = requiredString(json, "world");
        if (world.isBlank() || world.length() > 128) {
            throw new IllegalArgumentException("Invalid TPA world name");
        }

        double x = requiredNumber(json, "x");
        double y = requiredNumber(json, "y");
        double z = requiredNumber(json, "z");
        float yaw = (float) requiredNumber(json, "yaw");
        float pitch = (float) requiredNumber(json, "pitch");
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
                || !Float.isFinite(yaw) || !Float.isFinite(pitch)
                || Math.abs(x) > MAX_HORIZONTAL_COORDINATE
                || Math.abs(z) > MAX_HORIZONTAL_COORDINATE
                || Math.abs(y) > MAX_VERTICAL_COORDINATE
                || pitch < -90.0f || pitch > 90.0f) {
            throw new IllegalArgumentException("TPA location is outside supported bounds");
        }
        return new TpaLocation(world, x, y, z, yaw, pitch);
    }

    private static String requiredString(JsonObject json, String key) {
        JsonElement value = json.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException("Missing or invalid TPA field: " + key);
        }
        return value.getAsString();
    }

    private static double requiredNumber(JsonObject json, String key) {
        JsonElement value = json.get(key);
        if (value == null || !value.isJsonPrimitive()) {
            throw new IllegalArgumentException("Missing or invalid TPA field: " + key);
        }
        JsonPrimitive primitive = value.getAsJsonPrimitive();
        if (!primitive.isNumber()) throw new IllegalArgumentException("Invalid TPA number: " + key);
        return primitive.getAsDouble();
    }
}
