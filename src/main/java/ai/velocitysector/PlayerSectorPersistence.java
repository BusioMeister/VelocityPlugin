package ai.velocitysector;

import com.mongodb.client.model.Filters;
import org.bson.Document;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Serializes preferred-sector read/update and disconnect saves per player UUID. */
final class PlayerSectorPersistence {
    private static final String DEFAULT_SECTOR = "Sector1";

    private final MongoDBManager database;
    private final AsyncKeyedSequencer<UUID> sequencer = new AsyncKeyedSequencer<>();

    PlayerSectorPersistence(MongoDBManager database) {
        this.database = database;
    }

    CompletableFuture<String> loadAndUpdate(UUID playerUuid, String playerName) {
        return sequencer.submit(playerUuid, () -> database.findOneAsync("users",
                        Filters.eq("uuid", playerUuid.toString()))
                .thenCompose(document -> {
                    String targetSector = preferredSector(document);
                    Document update = new Document("$set", new Document("name", playerName)
                            .append("sector", targetSector));
                    return database.updateOneByUuidAsync("users", playerUuid.toString(), update)
                            .thenApply(ignored -> targetSector);
                }));
    }

    CompletableFuture<Void> saveLastSector(UUID playerUuid, String playerName, String sector) {
        if (!isValidSectorName(sector)) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Invalid sector name"));
        }
        return sequencer.submit(playerUuid, () -> database.updateOneByUuidAsync("users", playerUuid.toString(),
                new Document("$set", new Document("sector", sector).append("name", playerName))));
    }

    private String preferredSector(Document document) {
        if (document != null && document.get("sector") instanceof String sector && isValidSectorName(sector)) {
            return sector;
        }
        return DEFAULT_SECTOR;
    }

    static boolean isValidSectorName(String sector) {
        return sector != null && sector.matches("[A-Za-z0-9_-]{1,64}");
    }
}
