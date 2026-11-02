package ai.aisector.shared;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TransferSaveRequestTest {
    private static final UUID PLAYER = UUID.fromString("95d3a452-0e1b-4c4d-bf8e-00b9fb69f1ca");

    @Test
    void acceptsOnlyBoundedStringRequestIdAndUuid() {
        assertEquals(PLAYER, TransferSaveRequest.parse("request-1", PLAYER.toString()).orElseThrow().playerUuid());
        assertTrue(TransferSaveRequest.parse(1, PLAYER.toString()).isEmpty());
        assertTrue(TransferSaveRequest.parse("", PLAYER.toString()).isEmpty());
        assertTrue(TransferSaveRequest.parse("x".repeat(129), PLAYER.toString()).isEmpty());
        assertTrue(TransferSaveRequest.parse("request-1", 7).isEmpty());
        assertTrue(TransferSaveRequest.parse("request-1", "not-a-uuid").isEmpty());
    }
}
