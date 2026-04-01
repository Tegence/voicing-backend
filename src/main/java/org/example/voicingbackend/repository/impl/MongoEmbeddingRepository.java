package org.example.voicingbackend.repository.impl;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import org.bson.Document;
import org.example.voicingbackend.config.ConfigurationManager;
import org.example.voicingbackend.repository.EmbeddingRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

public class MongoEmbeddingRepository implements EmbeddingRepository, AutoCloseable {
    private static final Logger logger = LoggerFactory.getLogger(MongoEmbeddingRepository.class);

    private final MongoClient client;
    private final MongoDatabase db;
    private final MongoCollection<Document> col;
    private final boolean ownsClient;

    /**
     * Creates a new MongoEmbeddingRepository with an externally provided MongoClient.
     * The caller retains ownership of the client; {@link #close()} will not close it.
     *
     * @param client       a shared MongoClient instance
     * @param databaseName the name of the MongoDB database to use
     */
    public MongoEmbeddingRepository(MongoClient client, String databaseName) {
        this.client = client;
        this.db = client.getDatabase(databaseName);
        this.col = db.getCollection("embeddings");
        this.ownsClient = false;

        logger.info("MongoDB embedding repository initialized with shared client");
    }

    /**
     * @deprecated Use {@link #MongoEmbeddingRepository(MongoClient, String)} to share a MongoClient.
     */
    @Deprecated
    public MongoEmbeddingRepository() {
        ConfigurationManager cfg = ConfigurationManager.getInstance();
        this.client = MongoClients.create(cfg.getMongoDbConnectionString());
        this.db = client.getDatabase(cfg.getMongoDbDatabaseName());
        this.col = db.getCollection("embeddings");
        this.ownsClient = true;
    }

    @Override
    public void save(String userId, String audioId, long startMs, long endMs, String modelVersion, float[] vector) {
        try {
            List<Double> vec = new ArrayList<>(vector.length);
            for (float v : vector) vec.add((double) v);
            Document d = new Document("userId", userId)
                    .append("audioId", audioId)
                    .append("startMs", startMs)
                    .append("endMs", endMs)
                    .append("modelVersion", modelVersion)
                    .append("embedding", vec)
                    .append("createdAt", new java.util.Date());
            col.insertOne(d);
        } catch (Exception e) {
            logger.error("Failed to save embedding: {}", e.getMessage(), e);
        }
    }

    @Override
    public List<float[]> findByUserId(String userId, int limit) {
        List<float[]> out = new ArrayList<>();
        try {
            var cursor = col.find(new Document("userId", userId)).limit(limit);
            for (Document d : cursor) {
                @SuppressWarnings("unchecked") List<Double> vec = (List<Double>) d.get("embedding");
                float[] arr = new float[vec.size()];
                for (int i = 0; i < vec.size(); i++) arr[i] = vec.get(i).floatValue();
                out.add(arr);
            }
        } catch (Exception e) {
            logger.error("Failed to fetch embeddings: {}", e.getMessage(), e);
        }
        return out;
    }

    /**
     * Closes the MongoDB connection if this repository owns the client.
     */
    @Override
    public void close() {
        if (ownsClient && client != null) {
            client.close();
            logger.info("MongoDB embedding connection closed");
        }
    }
}


