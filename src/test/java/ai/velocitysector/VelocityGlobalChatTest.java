package ai.velocitysector;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VelocityGlobalChatTest {
    @Test
    void acceptsOnlyProtocolSizedPluginMessages() {
        assertFalse(VelocityGlobalChat.isValidPayloadLength(-1));
        assertFalse(VelocityGlobalChat.isValidPayloadLength(0));
        assertFalse(VelocityGlobalChat.isValidPayloadLength(1));
        assertTrue(VelocityGlobalChat.isValidPayloadLength(2));
        assertTrue(VelocityGlobalChat.isValidPayloadLength(32_767));
        assertFalse(VelocityGlobalChat.isValidPayloadLength(32_768));
    }
}
