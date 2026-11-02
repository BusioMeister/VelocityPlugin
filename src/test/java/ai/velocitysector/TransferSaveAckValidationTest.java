package ai.velocitysector;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TransferSaveAckValidationTest {
    private static final UUID PLAYER = UUID.fromString("95d3a452-0e1b-4c4d-bf8e-00b9fb69f1ca");

    @Test
    void requiresAStringMatchingUuidAndBooleanSuccess() {
        assertTrue(NetworkListener.acknowledgementMatchesPlayer(JsonParser.parseString(
                "{\"uuid\":\"" + PLAYER + "\",\"success\":true}").getAsJsonObject(), PLAYER));
        assertFalse(NetworkListener.acknowledgementMatchesPlayer(JsonParser.parseString(
                "{\"uuid\":\"95d3a452-0e1b-4c4d-bf8e-00b9fb69f1cb\",\"success\":true}").getAsJsonObject(), PLAYER));
        assertFalse(NetworkListener.acknowledgementMatchesPlayer(JsonParser.parseString(
                "{\"uuid\":\"" + PLAYER + "\",\"success\":\"true\"}").getAsJsonObject(), PLAYER));
        assertFalse(NetworkListener.acknowledgementMatchesPlayer(JsonParser.parseString(
                "{\"uuid\":7,\"success\":true}").getAsJsonObject(), PLAYER));
    }
}
