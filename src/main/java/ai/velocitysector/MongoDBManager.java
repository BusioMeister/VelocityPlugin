package ai.velocitysector;

import ai.aisector.shared.database.MongoDatabaseManager;

/** Velocity module name retained while MongoDB operations live in the shared module. */
public final class MongoDBManager extends MongoDatabaseManager {
    public MongoDBManager(String uri, String databaseName) {
        super(uri, databaseName);
    }
}
