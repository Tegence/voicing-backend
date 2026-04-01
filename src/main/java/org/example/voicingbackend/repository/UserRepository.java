package org.example.voicingbackend.repository;

import org.example.voicingbackend.model.User;
import org.bson.types.ObjectId;

import java.util.List;
import java.util.Optional;

/**
 * Repository interface for User entity operations
 */
public interface UserRepository {
    
    /**
     * Creates a new user
     */
    Optional<User> createUser(User user);
    
    /**
     * Finds a user by username
     */
    Optional<User> findByUsername(String username);
    
    /**
     * Finds a user by email
     */
    Optional<User> findByEmail(String email);
    
    /**
     * Finds a user by ID
     */
    Optional<User> findById(ObjectId id);
    
    /**
     * Updates user's last login time
     */
    boolean updateLastLogin(ObjectId userId);
    
    /**
     * Updates user information
     */
    boolean updateUser(User user);
    
    /**
     * Deactivates a user
     */
    boolean deactivateUser(ObjectId userId);
    
    /**
     * Gets all active users
     */
    List<User> findAllActiveUsers();

    /**
     * Gets active users with pagination
     */
    List<User> findAllActiveUsers(int page, int pageSize);
    
    /**
     * Checks if a user exists and is active
     */
    boolean isUserActive(ObjectId userId);
    
    /**
     * Checks if username exists
     */
    boolean existsByUsername(String username);
    
    /**
     * Checks if email exists
     */
    boolean existsByEmail(String email);
}
