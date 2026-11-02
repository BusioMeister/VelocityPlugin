package ai.aisector.shared;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NetworkAddressUtilTest {
    @Test
    void normalizesValidIpv4AndLeadingZeros() {
        assertEquals("192.168.1.4", NetworkAddressUtil.normalizeIpv4("192.168.001.004").orElseThrow());
        assertEquals("0.0.0.0", NetworkAddressUtil.normalizeIpv4("0.0.0.0").orElseThrow());
        assertEquals("255.255.255.255", NetworkAddressUtil.normalizeIpv4("255.255.255.255").orElseThrow());
    }

    @Test
    void rejectsMalformedAndOutOfRangeIpv4() {
        assertTrue(NetworkAddressUtil.normalizeIpv4("256.1.1.1").isEmpty());
        assertTrue(NetworkAddressUtil.normalizeIpv4("1.2.3").isEmpty());
        assertTrue(NetworkAddressUtil.normalizeIpv4("1.2.3.4.5").isEmpty());
        assertTrue(NetworkAddressUtil.normalizeIpv4("1..3.4").isEmpty());
        assertTrue(NetworkAddressUtil.normalizeIpv4("1.2.a.4").isEmpty());
        assertTrue(NetworkAddressUtil.normalizeIpv4(" 1.2.3.4").isEmpty());
    }

    @Test
    void distinguishesNumericIpv4CandidatesFromNames() {
        assertTrue(NetworkAddressUtil.looksLikeIpv4Literal("999.1.1.1"));
        assertTrue(NetworkAddressUtil.looksLikeIpv4Literal("..."));
        assertFalse(NetworkAddressUtil.looksLikeIpv4Literal("player1"));
        assertFalse(NetworkAddressUtil.looksLikeIpv4Literal("example.com"));
    }

    @Test
    void normalizesIpv6LiteralsWithoutAcceptingHostnames() {
        String compressed = NetworkAddressUtil.normalizeIpLiteral("2001:db8::1").orElseThrow();
        String expanded = NetworkAddressUtil.normalizeIpLiteral("2001:0db8:0:0:0:0:0:1").orElseThrow();
        assertEquals(compressed, expanded);
        assertTrue(NetworkAddressUtil.normalizeIpLiteral("::1").isPresent());
        assertTrue(NetworkAddressUtil.normalizeIpLiteral("2001::gg").isEmpty());
        assertTrue(NetworkAddressUtil.normalizeIpLiteral("example.com").isEmpty());
        assertTrue(NetworkAddressUtil.looksLikeIpLiteral("2001::gg"));
    }
}
