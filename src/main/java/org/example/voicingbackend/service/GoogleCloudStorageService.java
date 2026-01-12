package org.example.voicingbackend.service;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.storage.*;
import org.example.voicingbackend.audiomodel.AudioFormat;
import org.example.voicingbackend.config.ConfigurationManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Collections;

/**
 * Service for Google Cloud Storage operations
 */
public class GoogleCloudStorageService {
    private static final Logger logger = LoggerFactory.getLogger(GoogleCloudStorageService.class);
    
    private final Storage storage;
    
    public GoogleCloudStorageService() {
        ConfigurationManager config = ConfigurationManager.getInstance();
        String credentialsPath = config.getGcsCredentialsPath();
        String projectId = config.getGcsProjectId();
        String configuredBucket = config.getGcsBucketName();
        logger.info("GCS config — projectId='{}', bucket='{}', credsPath='{}'",
                projectId, configuredBucket, credentialsPath);
        
        Storage tempStorage;
        try {
            GoogleCredentials credentials = loadCredentials(credentialsPath);
            StorageOptions.Builder builder = StorageOptions.newBuilder()
                    .setProjectId(projectId);
            if (credentials != null) {
                builder.setCredentials(credentials);
                logger.info("Initialized GCS client with explicit credentials: {}", credentialsPath);
            } else {
                logger.info("Initialized GCS client with default credentials");
            }
            tempStorage = builder.build().getService();
        } catch (Exception e) {
            logger.warn("Failed to initialize explicit credentials, falling back to default: {}", e.getMessage());
            tempStorage = StorageOptions.getDefaultInstance().getService();
        }
        this.storage = tempStorage;
    }

    private GoogleCredentials loadCredentials(String path) {
        try {
            if (path == null || path.isBlank()) {
                return null;
            }
            // Prefer filesystem path
            if (Files.exists(Paths.get(path))) {
                try (InputStream is = new FileInputStream(path)) {
                    return GoogleCredentials.fromStream(is).createScoped(Collections.singletonList("https://www.googleapis.com/auth/cloud-platform"));
                }
            }
            // Fallback to classpath resource
            try (InputStream is = getClass().getClassLoader().getResourceAsStream(path)) {
                if (is != null) {
                    return GoogleCredentials.fromStream(is).createScoped(Collections.singletonList("https://www.googleapis.com/auth/cloud-platform"));
                }
            }
        } catch (Exception e) {
            logger.warn("Could not load credentials from {}: {}", path, e.getMessage());
        }
        return null;
    }
    
    /**
     * Saves audio data to Google Cloud Storage
     */
    public SaveAudioResult saveAudioToGCS(float[] audioSamples, int sampleRate, String bucketName,
                                        String fileName, AudioFormat format, Map<String, String> metadata,
                                        long timestamp) {
        try {
            // Always resolve bucket from application properties
            ConfigurationManager cfg = ConfigurationManager.getInstance();
            String resolvedBucket = cfg.getGcsBucketName();
            if (resolvedBucket == null || resolvedBucket.isBlank() ||
                "your-audio-bucket-name".equals(resolvedBucket)) {
                String msg = "GCS bucket name is not configured. Set 'gcs.bucket.name' in application.properties.";
                logger.error(msg + " Current value='{}'", resolvedBucket);
                return new SaveAudioResult(false, null, fileName, 0, msg);
            }
            logger.debug("Resolved GCS bucket='{}' from configuration", resolvedBucket);

            // Username is required to place the object under user's folder
            String username = metadata != null ? metadata.get("username") : null;
            if (username == null || username.isBlank()) {
                return new SaveAudioResult(false, null, fileName, 0, "metadata.username is required");
            }
            // Basic path sanitization for username to avoid traversal or invalid chars
            String safeUser = username.replaceAll("[^a-zA-Z0-9._-]", "_");
            String objectName = safeUser + "/" + fileName;

            logger.info("Saving audio to GCS: bucket={}, object={}, format={}", resolvedBucket, objectName, format);
            
            // Verify bucket access (no auto-create to surface permission errors clearly)
            Bucket bucket = storage.get(resolvedBucket);
            logger.info("Bucket gotten: {}", bucket);
            if (bucket == null) {
                String msg = "Bucket not found or no access: " + resolvedBucket;
                logger.error(msg);
                return new SaveAudioResult(false, null, fileName, 0, msg);
            }
            
            // Convert audio samples to bytes
            logger.info("Begin  conversion of audio");
            byte[] audioBytes = convertAudioToBytes(audioSamples, sampleRate, format);
            logger.info("End conversion of audio");

            logger.info("Prepared {} bytes for upload (samples={}, rate={})", audioBytes.length, audioSamples.length, sampleRate);
            
            // Create blob info with metadata
            Map<String, String> allMetadata = new HashMap<>(metadata);
            
            // Add timestamp metadata
            if (timestamp > 0) {
                allMetadata.put("timestamp", String.valueOf(timestamp));
            }
            
            BlobInfo.Builder blobInfoBuilder = BlobInfo.newBuilder(BlobId.of(resolvedBucket, objectName))
                .setContentType(getContentType(format))
                .setMetadata(allMetadata);
            
            BlobInfo blobInfo = blobInfoBuilder.build();

            // Upload to GCS
            Blob blob = storage.create(blobInfo, audioBytes);
            logger.debug("storage.create completed: blob={} size={}B", blob != null ? blob.getName() : null, audioBytes.length);
            if (blob == null) {
                String msg = "Upload returned null Blob for object: " + objectName;
                logger.error(msg);
                return new SaveAudioResult(false, null, objectName, 0, msg);
            }
            logger.info("Audio saved successfully to GCS: {}", blob.getName());
            
            String gcsUri = "gs://" + resolvedBucket + "/" + objectName;
            return new SaveAudioResult(true, gcsUri, objectName, audioBytes.length, null);
            
        } catch (com.google.cloud.storage.StorageException se) {
            logger.error("GCS error code={}, reason={}, location={}, msg={}", se.getCode(), se.getReason(), se.getLocation(), se.getMessage(), se);
            return new SaveAudioResult(false, null, fileName, 0, "GCS " + se.getCode() + " " + se.getReason() + ": " + se.getMessage());
        } catch (Exception e) {
            logger.error("Failed to save audio to GCS: {}", e.getMessage(), e);
            return new SaveAudioResult(false, null, fileName, 0, e.getMessage());
        }
    }
    
    /**
     * Converts audio samples to bytes based on format
     */
    private byte[] convertAudioToBytes(float[] audioSamples, int sampleRate, AudioFormat format) throws IOException {
        switch (format) {
            case WAV:
                return convertToWav(audioSamples, sampleRate);
            case RAW:
                return convertToRaw(audioSamples);
            case MP3:
            case FLAC:
            case OGG:
                // For now, convert to WAV as these formats require additional libraries
                logger.warn("Format {} not fully supported, converting to WAV", format);
                return convertToWav(audioSamples, sampleRate);
            default:
                throw new IllegalArgumentException("Unsupported audio format: " + format);
        }
    }
    
    /**
     * Converts audio samples to WAV format
     */
    private byte[] convertToWav(float[] audioSamples, int sampleRate) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        
        // WAV header (44 bytes)
        writeWavHeader(baos, audioSamples.length, sampleRate);
        
        // Audio data
        for (float sample : audioSamples) {
            // Convert float [-1, 1] to 16-bit PCM
            short pcmSample = (short) (sample * 32767.0f);
            baos.write(pcmSample & 0xFF);
            baos.write((pcmSample >> 8) & 0xFF);
        }
        
        return baos.toByteArray();
    }
    
    /**
     * Writes WAV header to output stream
     */
    private void writeWavHeader(ByteArrayOutputStream baos, int numSamples, int sampleRate) throws IOException {
        // RIFF header
        baos.write("RIFF".getBytes());
        writeInt(baos, 36 + numSamples * 2); // File size - 8
        baos.write("WAVE".getBytes());
        
        // Format chunk
        baos.write("fmt ".getBytes());
        writeInt(baos, 16); // Chunk size
        writeShort(baos, 1); // Audio format (PCM)
        writeShort(baos, 1); // Number of channels
        writeInt(baos, sampleRate); // Sample rate
        writeInt(baos, sampleRate * 2); // Byte rate
        writeShort(baos, 2); // Block align
        writeShort(baos, 16); // Bits per sample
        
        // Data chunk
        baos.write("data".getBytes());
        writeInt(baos, numSamples * 2); // Data size
    }
    
    /**
     * Converts audio samples to raw PCM format
     */
    private byte[] convertToRaw(float[] audioSamples) {
        byte[] rawBytes = new byte[audioSamples.length * 4]; // 32-bit float
        ByteBuffer buffer = ByteBuffer.wrap(rawBytes).order(ByteOrder.LITTLE_ENDIAN);
        
        for (float sample : audioSamples) {
            buffer.putFloat(sample);
        }
        
        return rawBytes;
    }
    
    /**
     * Gets content type for audio format
     */
    private String getContentType(AudioFormat format) {
        switch (format) {
            case WAV:
                return "audio/wav";
            case MP3:
                return "audio/mpeg";
            case FLAC:
                return "audio/flac";
            case OGG:
                return "audio/ogg";
            case RAW:
                return "application/octet-stream";
            default:
                return "application/octet-stream";
        }
    }
    
    /**
     * Checks if bucket exists
     */
    private boolean bucketExists(String bucketName) {
        try {
            Bucket bucket = storage.get(bucketName);
            logger.info("Bucket exists: {}", bucketName);
            return bucket != null;
        } catch (Exception e) {
            logger.error("Bucket does not exist: {}", bucketName, e);
            return false;
        }
    }
    
    /**
     * Creates bucket if it doesn't exist
     */
    private boolean createBucketIfNotExists(String bucketName) {
        try {
            if (bucketExists(bucketName)) {
                return true;
            }
            Bucket bucket = storage.create(BucketInfo.of(bucketName));
            logger.info("Created GCS bucket: {}", bucket.getName());
            return true;
        } catch (Exception e) {
            logger.error("Failed to create bucket {}: {}", bucketName, e.getMessage(), e);
            return false;
        }
    }
    
    /**
     * Writes integer in little-endian format
     */
    private void writeInt(ByteArrayOutputStream baos, int value) throws IOException {
        baos.write(value & 0xFF);
        baos.write((value >> 8) & 0xFF);
        baos.write((value >> 16) & 0xFF);
        baos.write((value >> 24) & 0xFF);
    }
    
    /**
     * Writes short in little-endian format
     */
    private void writeShort(ByteArrayOutputStream baos, int value) throws IOException {
        baos.write(value & 0xFF);
        baos.write((value >> 8) & 0xFF);
    }
    
    /**
     * Result of save audio operation
     */
    public static class SaveAudioResult {
        private final boolean success;
        private final String gcsUri;
        private final String fileName;
        private final long fileSizeBytes;
        private final String errorMessage;
        
        public SaveAudioResult(boolean success, String gcsUri, String fileName, long fileSizeBytes, String errorMessage) {
            this.success = success;
            this.gcsUri = gcsUri;
            this.fileName = fileName;
            this.fileSizeBytes = fileSizeBytes;
            this.errorMessage = errorMessage;
        }
        
        public boolean isSuccess() { return success; }
        public String getGcsUri() { return gcsUri; }
        public String getFileName() { return fileName; }
        public long getFileSizeBytes() { return fileSizeBytes; }
        public String getErrorMessage() { return errorMessage; }
    }
}
