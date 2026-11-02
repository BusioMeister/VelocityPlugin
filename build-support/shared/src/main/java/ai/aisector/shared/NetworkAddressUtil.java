package ai.aisector.shared;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Optional;

/** Helpers for parsing numeric network-address literals without DNS lookups. */
public final class NetworkAddressUtil {
    private NetworkAddressUtil() { }

    public static boolean looksLikeIpv4Literal(String input) {
        return input != null && input.indexOf('.') >= 0 && input.matches("[0-9.]+");
    }

    public static boolean looksLikeIpLiteral(String input) {
        return looksLikeIpv4Literal(input) || input != null && input.indexOf(':') >= 0;
    }

    public static Optional<String> normalizeIpv4(String input) {
        if (input == null) return Optional.empty();
        String[] octets = input.split("\\.", -1);
        if (octets.length != 4) return Optional.empty();

        StringBuilder normalized = new StringBuilder();
        for (int index = 0; index < octets.length; index++) {
            String octet = octets[index];
            if (octet.isEmpty() || !octet.matches("[0-9]{1,3}")) return Optional.empty();
            int value;
            try {
                value = Integer.parseInt(octet);
            } catch (NumberFormatException exception) {
                return Optional.empty();
            }
            if (value > 255) return Optional.empty();
            if (index > 0) normalized.append('.');
            normalized.append(value);
        }
        return Optional.of(normalized.toString());
    }

    public static Optional<String> normalizeIpLiteral(String input) {
        if (looksLikeIpv4Literal(input)) return normalizeIpv4(input);
        if (input == null || input.indexOf(':') < 0 || !input.matches("[0-9a-fA-F:.]+")) {
            return Optional.empty();
        }
        try {
            // The strict numeric-only alphabet and required colon prevent hostname/DNS resolution.
            return Optional.of(InetAddress.getByName(input).getHostAddress());
        } catch (UnknownHostException exception) {
            return Optional.empty();
        }
    }
}
