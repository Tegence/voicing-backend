package org.example.voicingbackend.model;

import org.bson.Document;
import org.bson.types.ObjectId;

import java.time.Instant;

/**
 * User entity model for MongoDB
 */
public class User {
    private ObjectId id;
    private String username;
    private String email;
    private String passwordHash;
    private String fullName;
    private Instant createdAt;
    private Instant lastLogin;
    private boolean active;
    
    public User() {
        this.createdAt = Instant.now();
        this.active = true;
    }
    
    public User(String username, String email, String passwordHash, String fullName) {
        this();
        this.username = username;
        this.email = email;
        this.passwordHash = passwordHash;
        this.fullName = fullName;
    }
    
    /**
     * Converts User to MongoDB Document
     */
    public Document toDocument() {
        Document doc = new Document();
        if (id != null) {
            doc.put("_id", id);
        }
        doc.put("username", username);
        doc.put("email", email);
        doc.put("passwordHash", passwordHash);
        doc.put("fullName", fullName);
        doc.put("createdAt", createdAt);
        doc.put("lastLogin", lastLogin);
        doc.put("active", active);
        return doc;
    }
    
    /**
     * Creates User from MongoDB Document
     */
    public static User fromDocument(Document doc) {
        User user = new User();
        user.id = doc.getObjectId("_id");
        user.username = doc.getString("username");
        user.email = doc.getString("email");
        user.passwordHash = doc.getString("passwordHash");
        user.fullName = doc.getString("fullName");
        Object created = doc.get("createdAt");
        if (created instanceof java.util.Date) {
            user.createdAt = ((java.util.Date) created).toInstant();
        } else if (created instanceof Instant) {
            user.createdAt = (Instant) created;
        }

        Object last = doc.get("lastLogin");
        if (last instanceof java.util.Date) {
            user.lastLogin = ((java.util.Date) last).toInstant();
        } else if (last instanceof Instant) {
            user.lastLogin = (Instant) last;
        }
        user.active = doc.getBoolean("active", true);
        return user;
    }
    
    // Getters and setters
    public ObjectId getId() { return id; }
    public void setId(ObjectId id) { this.id = id; }
    
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    
    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }
    
    public String getFullName() { return fullName; }
    public void setFullName(String fullName) { this.fullName = fullName; }
    
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    
    public Instant getLastLogin() { return lastLogin; }
    public void setLastLogin(Instant lastLogin) { this.lastLogin = lastLogin; }
    
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
    
    @Override
    public String toString() {
        return "User{" +
                "id=" + id +
                ", username='" + username + '\'' +
                ", email='" + email + '\'' +
                ", fullName='" + fullName + '\'' +
                ", createdAt=" + createdAt +
                ", lastLogin=" + lastLogin +
                ", active=" + active +
                '}';
    }
}
