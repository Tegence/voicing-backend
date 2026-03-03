package org.example.voicingbackend.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * Configuration manager for application settings
 */
public class ConfigurationManager {
    private static final Logger logger = LoggerFactory.getLogger(ConfigurationManager.class);
    private static ConfigurationManager instance;
    private final Properties properties;
    
    private ConfigurationManager() {
        this.properties = new Properties();
        loadConfiguration();
    }
    
    public static synchronized ConfigurationManager getInstance() {
        if (instance == null) {
            instance = new ConfigurationManager();
        }
        return instance;
    }
    
    /**
     * Loads configuration from application.properties file
     */
    private void loadConfiguration() {
        try (InputStream input = getClass().getClassLoader().getResourceAsStream("application.properties")) {
            if (input != null) {
                properties.load(input);
                logger.info("Configuration loaded from application.properties");
            } else {
                logger.warn("application.properties not found, using default configuration");
                setDefaultProperties();
            }
        } catch (IOException e) {
            logger.error("Failed to load configuration: {}", e.getMessage(), e);
            setDefaultProperties();
        }
        
        validateConfiguration();
    }
    
    /**
     * Sets default properties if configuration file is not found
     */
    private void setDefaultProperties() {
        properties.setProperty("gcs.project.id", "your-gcp-project-id");
        properties.setProperty("gcs.bucket.name", "your-audio-bucket-name");
        properties.setProperty("gcs.credentials.path", "/path/to/your/service-account-key.json");
        properties.setProperty("audio.default.sample.rate", "16000");
        properties.setProperty("audio.default.format", "WAV");
        properties.setProperty("audio.max.file.size.mb", "100");
        properties.setProperty("server.port", "9090");
        properties.setProperty("server.host", "0.0.0.0");
        properties.setProperty("mongodb.connection.string", "mongodb+srv://obolamatanmi:walnutbed@cluster0.oto9dja.mongodb.net/?appName=Cluster0");
        properties.setProperty("mongodb.database.name", "voicing_backend");
        properties.setProperty("jwt.secret.key", "your-secret-key-should-be-at-least-256-bits-long-for-production");
        properties.setProperty("jwt.expiration.hours", "24");
    }
    
    /**
     * Validates essential configuration settings
     */
    private void validateConfiguration() {
        logger.info("Validating configuration...");
        
        // Check GCS configuration
        String gcsProjectId = getGcsProjectId();
        String gcsBucketName = getGcsBucketName();
        String gcsCredentialsPath = getGcsCredentialsPath();
        
        if (gcsProjectId.equals("your-gcp-project-id")) {
            logger.warn("GCS project ID not configured, using default");
        }
        
        if (gcsBucketName.equals("your-audio-bucket-name")) {
            logger.warn("GCS bucket name not configured, using default");
        }
        
        if (gcsCredentialsPath.equals("/path/to/your/service-account-key.json")) {
            logger.warn("GCS credentials path not configured, using default");
        }
        
        logger.info("Configuration validation completed");
    }
    
    // Getters for configuration values
    public String getString(String key, String defaultValue) {
        String value = properties.getProperty(key, defaultValue);
        return resolveEnvVariables(value);
    }

    /**
     * Resolves environment variable placeholders in the format ${ENV_VAR:default}
     */
    private String resolveEnvVariables(String value) {
        if (value == null || !value.contains("${")) {
            return value;
        }

        // Pattern: ${ENV_VAR:default} or ${ENV_VAR}
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("\\$\\{([^:}]+)(?::([^}]*))?\\}");
        java.util.regex.Matcher matcher = pattern.matcher(value);
        StringBuffer result = new StringBuffer();

        while (matcher.find()) {
            String envVar = matcher.group(1);
            String defaultVal = matcher.group(2) != null ? matcher.group(2) : "";
            String envValue = System.getenv(envVar);
            String replacement = (envValue != null && !envValue.isEmpty()) ? envValue : defaultVal;
            matcher.appendReplacement(result, java.util.regex.Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(result);

        return result.toString();
    }
    
    public int getInt(String key, int defaultValue) {
        try {
            return Integer.parseInt(properties.getProperty(key, String.valueOf(defaultValue)));
        } catch (NumberFormatException e) {
            logger.warn("Invalid integer value for key '{}', using default: {}", key, defaultValue);
            return defaultValue;
        }
    }
    
    public long getLong(String key, long defaultValue) {
        try {
            return Long.parseLong(properties.getProperty(key, String.valueOf(defaultValue)));
        } catch (NumberFormatException e) {
            logger.warn("Invalid long value for key '{}', using default: {}", key, defaultValue);
            return defaultValue;
        }
    }
    
    public double getDouble(String key, double defaultValue) {
        try {
            return Double.parseDouble(properties.getProperty(key, String.valueOf(defaultValue)));
        } catch (NumberFormatException e) {
            logger.warn("Invalid double value for key '{}', using default: {}", key, defaultValue);
            return defaultValue;
        }
    }
    
    public boolean getBoolean(String key, boolean defaultValue) {
        return Boolean.parseBoolean(properties.getProperty(key, String.valueOf(defaultValue)));
    }
    
    // Specific getters for common configuration values
    public String getGcsProjectId() {
        return getString("gcs.project.id", "your-gcp-project-id");
    }
    
    public String getGcsBucketName() {
        return getString("gcs.bucket.name", "your-audio-bucket-name");
    }
    
    public String getGcsCredentialsPath() {
        return getString("gcs.credentials.path", "/path/to/your/service-account-key.json");
    }
    
    public int getAudioDefaultSampleRate() {
        return getInt("audio.default.sample.rate", 16000);
    }
    
    public String getAudioDefaultFormat() {
        return getString("audio.default.format", "WAV");
    }
    
    public int getAudioMaxFileSizeMb() {
        return getInt("audio.max.file.size.mb", 100);
    }
    
    public int getServerPort() {
        // Check PORT environment variable first (set by Cloud Run)
        String portEnv = System.getenv("PORT");
        if (portEnv != null && !portEnv.isEmpty()) {
            try {
                return Integer.parseInt(portEnv);
            } catch (NumberFormatException e) {
                // Fall through to default
            }
        }
        return getInt("server.port", 9090);
    }
    
    public String getServerHost() {
        return getString("server.host", "0.0.0.0");
    }
    
    public String getMongoDbConnectionString() {
        return getString("mongodb.connection.string", "mongodb+srv://obolamatanmi:walnutbed@cluster0.oto9dja.mongodb.net/?appName=Cluster0");
    }
    
    public String getMongoDbDatabaseName() {
        return getString("mongodb.database.name", "voicing_backend");
    }
    
    public String getJwtSecretKey() {
        return getString("jwt.secret.key", "your-secret-key-should-be-at-least-256-bits-long-for-production");
    }
    
    public int getJwtExpirationHours() {
        return getInt("jwt.expiration.hours", 24);
    }

    // AWS / S3
    public String getAwsRegion() { return getString("aws.region", "eu-north-1"); }
    public String getS3BucketName() { return getString("s3.bucket.name", "voicing-audio-bucket"); }
    public String getAwsAccessKeyId() { return getString("aws.accessKeyId", "samiat.bola-matanmi@tegence.com"); }
    public String getAwsSecretAccessKey() { return getString("aws.secretAccessKey", "Walnutbed213$ "); }
    public String getS3Endpoint() { return getString("s3.endpoint", ""); } // optional for S3-compatible

    // Verification threshold (%): 0-100
    public int getVerificationThresholdPercent() { return getInt("audio.verify.threshold", 70); }
    public double getVerifyWindowSimThreshold() { return getDouble("audio.verify.window.sim.threshold", 0.6); }
    public int getVerifyMinSpeechWindows() { return getInt("audio.verify.min.speech.windows", 3); }
    public double getVerifyTopPercent() { return getDouble("audio.verify.top.percent", 0.3); }
}
