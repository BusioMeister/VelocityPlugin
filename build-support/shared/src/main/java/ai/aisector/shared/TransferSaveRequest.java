package ai.aisector.shared;

import java.util.Optional;
import java.util.UUID;

/** Validated shared contract for a proxy request to snapshot a player's Paper state. */
public record TransferSaveRequest(String requestId, UUID playerUuid) {
    public static Optional<TransferSaveRequest> parse(Object requestIdValue, Object playerUuidValue) {
        if (!(requestIdValue instanceof String requestId) || requestId.isBlank() || requestId.length() > 128
                || !(playerUuidValue instanceof String uuidValue) || uuidValue.length() > 36) {
            return Optional.empty();
        }
        try {
            return Optional.of(new TransferSaveRequest(requestId, UUID.fromString(uuidValue)));
        } catch (IllegalArgumentException malformedUuid) {
            return Optional.empty();
        }
    }
}
