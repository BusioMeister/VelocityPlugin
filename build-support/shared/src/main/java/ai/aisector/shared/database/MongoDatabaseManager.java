package ai.aisector.shared.database;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.ReplaceOptions;
import com.mongodb.client.model.UpdateOptions;
import com.mongodb.client.result.DeleteResult;
import com.mongodb.client.result.UpdateResult;
import org.bson.Document;
import org.bson.conversions.Bson;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/** Shared synchronous MongoDB operations used by both network plugins. */
public class MongoDatabaseManager implements AutoCloseable {
    private final MongoClient client;
    private final MongoDatabase database;
    private final ExecutorService ioExecutor = new ThreadPoolExecutor(4, 4, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(4096), runnable -> {
                Thread thread = new Thread(runnable, "aiSector-mongo-io");
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());
    private final AtomicBoolean closed = new AtomicBoolean();

    public MongoDatabaseManager(String uri, String databaseName) {
        this.client = MongoClients.create(Objects.requireNonNull(uri, "uri"));
        this.database = client.getDatabase(Objects.requireNonNull(databaseName, "databaseName"));
    }

    public MongoCollection<Document> getCollection(String collectionName) {
        return database.getCollection(collectionName);
    }

    public void insertOne(String collectionName, Document document) {
        getCollection(collectionName).insertOne(document);
    }

    public CompletableFuture<Void> insertOneAsync(String collectionName, Document document) {
        Document snapshot = BsonSnapshot.document(document);
        return executeAsync(() -> insertOne(collectionName, snapshot));
    }

    public CompletableFuture<Void> replaceOneAsync(String collectionName, Bson filter, Document replacement,
                                                  ReplaceOptions options) {
        Document snapshot = BsonSnapshot.document(replacement);
        Bson filterSnapshot = filter instanceof Document document ? BsonSnapshot.document(document) : filter;
        return executeAsync((Runnable) () -> getCollection(collectionName)
                .replaceOne(filterSnapshot, snapshot, options));
    }

    public void insertMany(String collectionName, List<Document> documents) {
        if (!documents.isEmpty()) {
            getCollection(collectionName).insertMany(documents);
        }
    }

    public List<Document> findAll(String collectionName) {
        return getCollection(collectionName).find().into(new ArrayList<>());
    }

    public Document findOne(String collectionName, Bson filter) {
        return getCollection(collectionName).find(filter).first();
    }

    public List<Document> findMany(String collectionName, Bson filter) {
        return getCollection(collectionName).find(filter).into(new ArrayList<>());
    }

    public List<Document> findMany(String collectionName, Bson filter, Bson sort, int limit) {
        var results = getCollection(collectionName).find(filter);
        if (sort != null) results.sort(sort);
        if (limit > 0) results.limit(limit);
        return results.into(new ArrayList<>());
    }

    public CompletableFuture<Document> findOneAsync(String collectionName, Bson filter) {
        return executeAsync(() -> findOne(collectionName, filter));
    }

    public CompletableFuture<List<Document>> findManyAsync(String collectionName, Bson filter) {
        return executeAsync(() -> findMany(collectionName, filter));
    }

    public CompletableFuture<List<Document>> findManyAsync(String collectionName, Bson filter, Bson sort, int limit) {
        return executeAsync(() -> findMany(collectionName, filter, sort, limit));
    }

    public void updateOne(String collectionName, Bson filter, Bson update) {
        getCollection(collectionName).updateOne(filter, update);
    }

    public CompletableFuture<UpdateResult> updateOneAsync(String collectionName, Bson filter, Bson update) {
        Bson filterSnapshot = filter instanceof Document document ? BsonSnapshot.document(document) : filter;
        Bson updateSnapshot = update instanceof Document document ? BsonSnapshot.document(document) : update;
        return executeAsync(() -> getCollection(collectionName).updateOne(filterSnapshot, updateSnapshot));
    }

    public CompletableFuture<UpdateResult> updateManyAsync(String collectionName, Bson filter, Bson update) {
        Bson filterSnapshot = filter instanceof Document document ? BsonSnapshot.document(document) : filter;
        Bson updateSnapshot = update instanceof Document document ? BsonSnapshot.document(document) : update;
        return executeAsync(() -> getCollection(collectionName).updateMany(filterSnapshot, updateSnapshot));
    }

    public CompletableFuture<DeleteResult> deleteOneAsync(String collectionName, Bson filter) {
        return executeAsync(() -> getCollection(collectionName).deleteOne(filter));
    }

    public CompletableFuture<DeleteResult> deleteManyAsync(String collectionName, Bson filter) {
        return executeAsync(() -> getCollection(collectionName).deleteMany(filter));
    }

    public void updateOneByUuid(String collectionName, String uuid, Document update) {
        var collection = getCollection(collectionName);
        if (!"users".equals(collectionName)) {
            collection.updateOne(Filters.eq("uuid", uuid), update, new UpdateOptions().upsert(true));
            return;
        }
        // Preserve legacy identities, but make all new profile creators contend on the same unique _id.
        // A uuid-filtered upsert alone can insert duplicates when no unique uuid index exists.
        if (collection.updateOne(Filters.eq("uuid", uuid), update).getMatchedCount() > 0) return;
        var identity = Filters.and(Filters.eq("_id", "user:" + uuid), Filters.eq("uuid", uuid));
        try {
            collection.updateOne(identity, update, new UpdateOptions().upsert(true));
        } catch (com.mongodb.MongoWriteException conflict) {
            if (conflict.getError().getCode() != 11000
                    || collection.updateOne(identity, update).getMatchedCount() == 0) throw conflict;
        }
    }

    public CompletableFuture<Void> updateOneByUuidAsync(String collectionName, String uuid, Document update) {
        Document snapshot = BsonSnapshot.document(update);
        return executeAsync(() -> updateOneByUuid(collectionName, uuid, snapshot));
    }

    public void upsertByUuid(String collectionName, String uuid, Document document) {
        getCollection(collectionName).replaceOne(Filters.eq("uuid", uuid), document,
                new ReplaceOptions().upsert(true));
    }

    public CompletableFuture<Void> upsertByUuidAsync(String collectionName, String uuid, Document document) {
        Document snapshot = BsonSnapshot.document(document);
        return executeAsync(() -> upsertByUuid(collectionName, uuid, snapshot));
    }

    public <T> CompletableFuture<T> executeAsync(Supplier<T> operation) {
        Objects.requireNonNull(operation, "operation");
        if (closed.get()) return CompletableFuture.failedFuture(new IllegalStateException("Mongo manager is closed"));
        try {
            return CompletableFuture.supplyAsync(operation, ioExecutor);
        } catch (RejectedExecutionException exception) {
            return CompletableFuture.failedFuture(exception);
        }
    }

    public CompletableFuture<Void> executeAsync(Runnable operation) {
        Objects.requireNonNull(operation, "operation");
        return executeAsync(() -> {
            operation.run();
            return null;
        });
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        ioExecutor.shutdown();
        try {
            if (!ioExecutor.awaitTermination(35, TimeUnit.SECONDS)) {
                ioExecutor.shutdownNow();
                ioExecutor.awaitTermination(5, TimeUnit.SECONDS);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            ioExecutor.shutdownNow();
        }
        client.close();
    }
}
