package ai.velocitysector;

import com.mongodb.Block;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.UpdateOptions;
import org.bson.Document;
import org.bson.conversions.Bson;

import java.util.ArrayList;
import java.util.List;



public class MongoDBManager {
    private MongoClient mongoClient;
    private MongoDatabase database;

    // Konstruktor - inicjalizacja połączenia
    public MongoDBManager(String uri, String databaseName) {
        mongoClient = MongoClients.create(uri);
        database = mongoClient.getDatabase(databaseName);
    }

    // Pobieranie kolekcji
    public MongoCollection<Document> getCollection(String collectionName) {
        return database.getCollection(collectionName);
    }

    // Metoda zapisu pojedynczego dokumentu
    public void insertOne(String collectionName, Document document) {
        MongoCollection<Document> collection = getCollection(collectionName);
        collection.insertOne(document);
        System.out.println("Dokument został dodany do kolekcji: " + collectionName);
    }

    // Metoda zapisu wielu dokumentów
    public void insertMany(String collectionName, List<Document> documents) {
        MongoCollection<Document> collection = getCollection(collectionName);
        collection.insertMany(documents);
        System.out.println("Dodano " + documents.size() + " dokumentów do kolekcji: " + collectionName);
    }

    // Metoda odczytu wszystkich dokumentów
    public List<Document> findAll(String collectionName) {
        MongoCollection<Document> collection = getCollection(collectionName);
        List<Document> documents = new ArrayList<>();

        // Używamy add() w jednoznaczny sposób
        collection.find().forEach((Block<? super Document>) document -> {
                    documents.add(document);
                }
        );
        return documents;
    }

    // Metoda do wyszukiwania jednego dokumentu
    public Document findOne(String collectionName, Bson filter) {
        MongoCollection<Document> collection = getCollection(collectionName);
        return collection.find(filter).first();
    }
    public void updateOneByUuid(String collectionName, String uuid, Document update) {
        getCollection(collectionName).updateOne(
                Filters.eq("uuid", uuid),
                update,
                new UpdateOptions().upsert(true) // Opcja upsert(true) stworzy dokument, jeśli nie istnieje
        );
    }

    // Zamknięcie połączenia
    public void close() {
        if (mongoClient != null) {
            mongoClient.close();
            System.out.println("Połączenie z MongoDB zostało zamknięte.");
        }
    }
    public void upsertByUuid(String collectionName, String uuid, Document updatedDocument) {
        MongoCollection<Document> collection = getCollection(collectionName);

        // Tworzymy filtr na podstawie wartości pola `uuid`
        Bson filter = Filters.eq("uuid", uuid);

        // Aktualizujemy dokument lub wstawiamy nowy, jeśli nie istnieje
        UpdateOptions options = new UpdateOptions().upsert(true);

        // Zmieniamy cały dokument na `updatedDocument` (zastępujemy dokument)
        collection.replaceOne(filter, updatedDocument, options);

        System.out.println("Dokument z uuid=" + uuid + " został zaktualizowany lub wstawiony.");
    }
}
