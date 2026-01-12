package org.example.voicingbackend.service;

import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import org.bson.types.ObjectId;
import org.example.voicingbackend.model.User;
import org.example.voicingbackend.repository.UserRepository;
import org.example.voicingbackend.repository.impl.MongoUserRepository;
import org.example.voicingbackend.config.ConfigurationManager;
import at.favre.lib.crypto.bcrypt.BCrypt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.SecretKey;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.Optional;

/**
 * Authentication service for user registration, login, and JWT token management
 */
public class AuthenticationService {
    private static final Logger logger = LoggerFactory.getLogger(AuthenticationService.class);
    
    private final UserRepository userRepository;
    private final SecretKey jwtSecretKey;
    private final int jwtExpirationHours;
    
    public AuthenticationService() {
        this.userRepository = new MongoUserRepository();
        
        ConfigurationManager config = ConfigurationManager.getInstance();
        String secretKey = config.getJwtSecretKey();
        this.jwtSecretKey = Keys.hmacShaKeyFor(secretKey.getBytes());
        this.jwtExpirationHours = config.getJwtExpirationHours();
        
        logger.info("Authentication service initialized");
    }
    
    /**
     * Registers a new user
     */
    public RegistrationResult registerUser(String username, String email, String password, String fullName) {
        try {
            // Validate input
            if (!isValidUsername(username)) {
                return new RegistrationResult(false, "Invalid username format", null, null);
            }
            
            if (!isValidEmail(email)) {
                return new RegistrationResult(false, "Invalid email format", null, null);
            }
            
            if (!isValidPassword(password)) {
                return new RegistrationResult(false, "Password must be at least 8 characters long", null, null);
            }
            
            // Check if username already exists
            if (userRepository.existsByUsername(username)) {
                return new RegistrationResult(false, "Username already exists", null, null);
            }
            
            // Check if email already exists
            if (userRepository.existsByEmail(email)) {
                return new RegistrationResult(false, "Email already exists", null, null);
            }
            
            // Hash password
            String passwordHash = BCrypt.withDefaults().hashToString(12, password.toCharArray());
            
            // Create user
            User user = new User(username, email, passwordHash, fullName);
            Optional<User> createdUser = userRepository.createUser(user);
            
            if (createdUser.isPresent()) {
                // Generate JWT token
                String token = generateToken(createdUser.get());
                
                logger.info("User registered successfully: {}", username);
                return new RegistrationResult(true, "User registered successfully", 
                    createdUser.get().getId().toString(), token);
            } else {
                return new RegistrationResult(false, "Failed to create user", null, null);
            }
            
        } catch (Exception e) {
            logger.error("Registration failed: {}", e.getMessage(), e);
            return new RegistrationResult(false, "Registration failed: " + e.getMessage(), null, null);
        }
    }
    
    /**
     * Authenticates a user login
     */
    public LoginResult loginUser(String username, String password) {
        try {
            // Find user by username
            Optional<User> userOpt = userRepository.findByUsername(username);
            if (userOpt.isEmpty()) {
                return new LoginResult(false, "Invalid username or password", null, null, null);
            }
            
            User user = userOpt.get();
            
            // Check if user is active
            if (!user.isActive()) {
                return new LoginResult(false, "Account is deactivated", null, null, null);
            }
            
            // Verify password
            BCrypt.Result result = BCrypt.verifyer().verify(password.toCharArray(), user.getPasswordHash());
            if (!result.verified) {
                return new LoginResult(false, "Invalid username or password", null, null, null);
            }
            
            // Update last login
            userRepository.updateLastLogin(user.getId());
            
            // Generate JWT token
            String token = generateToken(user);
            
            // Create user info
            UserInfo userInfo = createUserInfo(user);
            
            logger.info("User logged in successfully: {}", username);
            return new LoginResult(true, "Login successful", user.getId().toString(), token, userInfo);
            
        } catch (Exception e) {
            logger.error("Login failed: {}", e.getMessage(), e);
            return new LoginResult(false, "Login failed: " + e.getMessage(), null, null, null);
        }
    }
    
    /**
     * Validates a JWT token
     */
    public TokenValidationResult validateToken(String token) {
        try {
            if (token == null || token.trim().isEmpty()) {
                return new TokenValidationResult(false, null, null, "Token is required");
            }
            
            // Remove "Bearer " prefix if present
            if (token.startsWith("Bearer ")) {
                token = token.substring(7);
            }
            
            // Parse and validate token
            Claims claims = Jwts.parser()
                .setSigningKey(jwtSecretKey)
                .build()
                .parseClaimsJws(token)
                .getBody();
            
            String userId = claims.getSubject();
            String username = claims.get("username", String.class);
            
            // Check if user exists and is active
            if (userId != null) {
                ObjectId objectId = new ObjectId(userId);
                if (userRepository.isUserActive(objectId)) {
                    return new TokenValidationResult(true, userId, username, "Token is valid");
                } else {
                    return new TokenValidationResult(false, null, null, "User account is deactivated");
                }
            } else {
                return new TokenValidationResult(false, null, null, "Invalid token");
            }
            
        } catch (ExpiredJwtException e) {
            logger.warn("Token expired: {}", e.getMessage());
            return new TokenValidationResult(false, null, null, "Token has expired");
        } catch (JwtException e) {
            logger.warn("Invalid token: {}", e.getMessage());
            return new TokenValidationResult(false, null, null, "Invalid token");
        } catch (Exception e) {
            logger.error("Token validation failed: {}", e.getMessage(), e);
            return new TokenValidationResult(false, null, null, "Token validation failed");
        }
    }
    
    /**
     * Generates a JWT token for a user
     */
    private String generateToken(User user) {
        Instant now = Instant.now();
        Instant expiration = now.plus(jwtExpirationHours, ChronoUnit.HOURS);
        
        return Jwts.builder()
            .setSubject(user.getId().toString())
            .claim("username", user.getUsername())
            .claim("email", user.getEmail())
            .setIssuedAt(Date.from(now))
            .setExpiration(Date.from(expiration))
            .signWith(jwtSecretKey)
            .compact();
    }
    
    /**
     * Creates UserInfo from User
     */
    private UserInfo createUserInfo(User user) {
        return new UserInfo(
            user.getId().toString(),
            user.getUsername(),
            user.getEmail(),
            user.getFullName(),
            user.getCreatedAt().toEpochMilli(),
            user.getLastLogin() != null ? user.getLastLogin().toEpochMilli() : 0
        );
    }
    
    /**
     * Validates username format
     */
    private boolean isValidUsername(String username) {
        return username != null && username.length() >= 3 && username.length() <= 30 
            && username.matches("^[a-zA-Z0-9_]+$");
    }
    
    /**
     * Validates email format
     */
    private boolean isValidEmail(String email) {
        return email != null && email.matches("^[A-Za-z0-9+_.-]+@(.+)$");
    }
    
    /**
     * Validates password strength
     */
    private boolean isValidPassword(String password) {
        return password != null && password.length() >= 8;
    }
    
    /**
     * Result of user registration
     */
    public static class RegistrationResult {
        private final boolean success;
        private final String message;
        private final String userId;
        private final String token;
        
        public RegistrationResult(boolean success, String message, String userId, String token) {
            this.success = success;
            this.message = message;
            this.userId = userId;
            this.token = token;
        }
        
        public boolean isSuccess() { return success; }
        public String getMessage() { return message; }
        public String getUserId() { return userId; }
        public String getToken() { return token; }
    }
    
    /**
     * Result of user login
     */
    public static class LoginResult {
        private final boolean success;
        private final String message;
        private final String userId;
        private final String token;
        private final UserInfo userInfo;
        
        public LoginResult(boolean success, String message, String userId, String token, UserInfo userInfo) {
            this.success = success;
            this.message = message;
            this.userId = userId;
            this.token = token;
            this.userInfo = userInfo;
        }
        
        public boolean isSuccess() { return success; }
        public String getMessage() { return message; }
        public String getUserId() { return userId; }
        public String getToken() { return token; }
        public UserInfo getUserInfo() { return userInfo; }
    }
    
    /**
     * Result of token validation
     */
    public static class TokenValidationResult {
        private final boolean valid;
        private final String userId;
        private final String username;
        private final String message;
        
        public TokenValidationResult(boolean valid, String userId, String username, String message) {
            this.valid = valid;
            this.userId = userId;
            this.username = username;
            this.message = message;
        }
        
        public boolean isValid() { return valid; }
        public String getUserId() { return userId; }
        public String getUsername() { return username; }
        public String getMessage() { return message; }
    }
    
    /**
     * User information DTO
     */
    public static class UserInfo {
        private final String userId;
        private final String username;
        private final String email;
        private final String fullName;
        private final long createdAt;
        private final long lastLogin;
        
        public UserInfo(String userId, String username, String email, String fullName, long createdAt, long lastLogin) {
            this.userId = userId;
            this.username = username;
            this.email = email;
            this.fullName = fullName;
            this.createdAt = createdAt;
            this.lastLogin = lastLogin;
        }
        
        public String getUserId() { return userId; }
        public String getUsername() { return username; }
        public String getEmail() { return email; }
        public String getFullName() { return fullName; }
        public long getCreatedAt() { return createdAt; }
        public long getLastLogin() { return lastLogin; }
    }
}
