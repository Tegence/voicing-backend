package org.example.voicingbackend.repository.impl;

import com.mongodb.client.*;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.result.InsertOneResult;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.example.voicingbackend.model.User;
import org.example.voicingbackend.repository.UserRepository;
import org.example.voicingbackend.config.ConfigurationManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * MongoDB implementation of UserRepository
 */
public class MongoUserRepository implements UserRepository {
    private static final Logger logger = LoggerFactory.getLogger(MongoUserRepository.class);
    
    private final MongoClient mongoClient;
    private final MongoDatabase database;
    private final MongoCollection<Document> usersCollection;
    
    public MongoUserRepository() {
        ConfigurationManager config = ConfigurationManager.getInstance();
        
        // Get MongoDB connection string from configuration
        String connectionString = config.getString("mongodb.connection.string", "${MONGODB_CONNECTION_STRING:mongodb://localhost:27017}");
        String databaseName = config.getString("mongodb.database.name", "voicing_backend");
        
        logger.info("Connecting to MongoDB: {}", connectionString);
        
        // Initialize MongoDB client
        this.mongoClient = MongoClients.create(connectionString);
        this.database = mongoClient.getDatabase(databaseName);
        this.usersCollection = database.getCollection("users");
        
        // Create indexes for better performance
        createIndexes();
        
        logger.info("MongoDB user repository initialized successfully");
    }
    
    /**
     * Creates necessary indexes for the users collection
     */
    private void createIndexes() {
        try {
            // Create unique index on username
            usersCollection.createIndex(Indexes.ascending("username"), new IndexOptions().unique(true));
            
            // Create unique index on email
            usersCollection.createIndex(Indexes.ascending("email"), new IndexOptions().unique(true));
            
            logger.info("MongoDB indexes created successfully");
        } catch (Exception e) {
            logger.warn("Failed to create indexes: {}", e.getMessage());
        }
    }
    
    @Override
    public Optional<User> createUser(User user) {
        try {
            // Check if username already exists
            if (existsByUsername(user.getUsername())) {
                logger.warn("Username already exists: {}", user.getUsername());
                return Optional.empty();
            }
            
            // Check if email already exists
            if (existsByEmail(user.getEmail())) {
                logger.warn("Email already exists: {}", user.getEmail());
                return Optional.empty();
            }
            
            // Insert user
            InsertOneResult result = usersCollection.insertOne(user.toDocument());
            user.setId(result.getInsertedId().asObjectId().getValue());
            
            logger.info("User created successfully: {}", user.getUsername());
            return Optional.of(user);
            
        } catch (Exception e) {
            logger.error("Failed to create user: {}", e.getMessage(), e);
            return Optional.empty();
        }
    }
    
    @Override
    public Optional<User> findByUsername(String username) {
        try {
            Document doc = usersCollection.find(Filters.eq("username", username)).first();
            return doc != null ? Optional.of(User.fromDocument(doc)) : Optional.empty();
        } catch (Exception e) {
            logger.error("Failed to find user by username: {}", e.getMessage(), e);
            return Optional.empty();
        }
    }
    
    @Override
    public Optional<User> findByEmail(String email) {
        try {
            Document doc = usersCollection.find(Filters.eq("email", email)).first();
            return doc != null ? Optional.of(User.fromDocument(doc)) : Optional.empty();
        } catch (Exception e) {
            logger.error("Failed to find user by email: {}", e.getMessage(), e);
            return Optional.empty();
        }
    }
    
    @Override
    public Optional<User> findById(ObjectId id) {
        try {
            Document doc = usersCollection.find(Filters.eq("_id", id)).first();
            return doc != null ? Optional.of(User.fromDocument(doc)) : Optional.empty();
        } catch (Exception e) {
            logger.error("Failed to find user by ID: {}", e.getMessage(), e);
            return Optional.empty();
        }
    }
    
    @Override
    public boolean updateLastLogin(ObjectId userId) {
        try {
            usersCollection.updateOne(
                Filters.eq("_id", userId),
                new Document("$set", new Document("lastLogin", new java.util.Date()))
            );
            return true;
        } catch (Exception e) {
            logger.error("Failed to update last login: {}", e.getMessage(), e);
            return false;
        }
    }
    
    @Override
    public boolean updateUser(User user) {
        try {
            usersCollection.replaceOne(
                Filters.eq("_id", user.getId()),
                user.toDocument()
            );
            return true;
        } catch (Exception e) {
            logger.error("Failed to update user: {}", e.getMessage(), e);
            return false;
        }
    }
    
    @Override
    public boolean deactivateUser(ObjectId userId) {
        try {
            usersCollection.updateOne(
                Filters.eq("_id", userId),
                new Document("$set", new Document("active", false))
            );
            return true;
        } catch (Exception e) {
            logger.error("Failed to deactivate user: {}", e.getMessage(), e);
            return false;
        }
    }
    
    @Override
    public List<User> findAllActiveUsers() {
        List<User> users = new ArrayList<>();
        try {
            usersCollection.find(Filters.eq("active", true))
                .forEach(doc -> users.add(User.fromDocument(doc)));
        } catch (Exception e) {
            logger.error("Failed to get active users: {}", e.getMessage(), e);
        }
        return users;
    }
    
    @Override
    public boolean isUserActive(ObjectId userId) {
        try {
            Document doc = usersCollection.find(
                Filters.and(
                    Filters.eq("_id", userId),
                    Filters.eq("active", true)
                )
            ).first();
            return doc != null;
        } catch (Exception e) {
            logger.error("Failed to check if user is active: {}", e.getMessage(), e);
            return false;
        }
    }
    
    @Override
    public boolean existsByUsername(String username) {
        try {
            return usersCollection.countDocuments(Filters.eq("username", username)) > 0;
        } catch (Exception e) {
            logger.error("Failed to check username existence: {}", e.getMessage(), e);
            return false;
        }
    }
    
    @Override
    public boolean existsByEmail(String email) {
        try {
            return usersCollection.countDocuments(Filters.eq("email", email)) > 0;
        } catch (Exception e) {
            logger.error("Failed to check email existence: {}", e.getMessage(), e);
            return false;
        }
    }
    
    /**
     * Closes the MongoDB connection
     */
    public void close() {
        if (mongoClient != null) {
            mongoClient.close();
            logger.info("MongoDB connection closed");
        }
    }
}
