package ai.velocitysector;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TpaLocationTest {
    @Test
    void parsesValidLocation() {
        JsonObject json = new JsonObject();
        json.addProperty("world", "world");
        json.addProperty("x", 30_000_000);
        json.addProperty("y", -64);
        json.addProperty("z", 12.5);
        json.addProperty("yaw", 180);
        json.addProperty("pitch", -90);

        TpaLocation location = TpaLocation.parse(json);

        assertEquals("world", location.world());
        assertEquals(30_000_000.0, location.x());
        assertEquals(-64.0, location.y());
        assertEquals(12.5, location.z());
        assertEquals(180.0f, location.yaw());
        assertEquals(-90.0f, location.pitch());
    }

    @Test
    void rejectsMissingFieldsAndWrongTypes() {
        JsonObject json = new JsonObject();
        json.addProperty("world", "world");
        json.addProperty("x", "1");
        assertThrows(IllegalArgumentException.class, () -> TpaLocation.parse(json));
    }

    @Test
    void rejectsNonFiniteAndOutOfRangeCoordinates() {
        JsonObject nonFinite = validJson();
        nonFinite.addProperty("x", Double.POSITIVE_INFINITY);
        assertThrows(IllegalArgumentException.class, () -> TpaLocation.parse(nonFinite));

        JsonObject tooFar = validJson();
        tooFar.addProperty("z", 30_000_001);
        assertThrows(IllegalArgumentException.class, () -> TpaLocation.parse(tooFar));

        JsonObject invalidPitch = validJson();
        invalidPitch.addProperty("pitch", 91);
        assertThrows(IllegalArgumentException.class, () -> TpaLocation.parse(invalidPitch));
    }

    private JsonObject validJson() {
        JsonObject json = new JsonObject();
        json.addProperty("world", "world");
        json.addProperty("x", 0);
        json.addProperty("y", 64);
        json.addProperty("z", 0);
        json.addProperty("yaw", 0);
        json.addProperty("pitch", 0);
        return json;
    }
}
