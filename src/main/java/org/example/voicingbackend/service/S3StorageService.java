package org.example.voicingbackend.service;

import org.example.voicingbackend.audiomodel.AudioFormat;
import org.example.voicingbackend.config.ConfigurationManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.Map;

public class S3StorageService {
    private static final Logger logger = LoggerFactory.getLogger(S3StorageService.class);

    private final S3Client s3;
    private final String bucket;

    public S3StorageService() {
        ConfigurationManager cfg = ConfigurationManager.getInstance();
        this.bucket = cfg.getS3BucketName();
        String region = cfg.getAwsRegion();
        String accessKey = cfg.getAwsAccessKeyId();
        String secretKey = cfg.getAwsSecretAccessKey();

        AwsCredentialsProvider creds;
        if (accessKey != null && !accessKey.isBlank() && secretKey != null && !secretKey.isBlank()) {
            creds = () -> AwsBasicCredentials.create(accessKey, secretKey);
        } else {
            creds = DefaultCredentialsProvider.create();
        }

        software.amazon.awssdk.services.s3.S3ClientBuilder builder = S3Client.builder()
                .region(region != null && !region.isBlank() ? Region.of(region) : Region.US_EAST_1)
                .credentialsProvider(creds);
        String endpoint = cfg.getS3Endpoint();
        if (endpoint != null && !endpoint.isBlank()) {
            builder = builder.endpointOverride(java.net.URI.create(endpoint));
        }
        this.s3 = builder.build();

        // Validate bucket
        try {
            s3.headBucket(HeadBucketRequest.builder().bucket(bucket).build());
            logger.info("S3 bucket verified: {}", bucket);
        } catch (NoSuchBucketException nsb) {
            logger.error("S3 bucket does not exist: {}", bucket);
        } catch (S3Exception e) {
            logger.error("S3 headBucket failed: code={} msg={}", e.awsErrorDetails().errorCode(), e.awsErrorDetails().errorMessage());
        }
    }

    public SaveResult save(float[] audioSamples, int sampleRate, String username, String fileName, AudioFormat format, Map<String, String> metadata, long timestamp) {
        try {
            if (bucket == null || bucket.isBlank()) {
                return new SaveResult(false, null, fileName, 0, "s3.bucket.name not configured");
            }
            if (username == null || username.isBlank()) {
                return new SaveResult(false, null, fileName, 0, "metadata.username is required");
            }
            String safeUser = username.replaceAll("[^a-zA-Z0-9._-]", "_");
            String key = safeUser + "/" + fileName;

            byte[] bytes = convertAudioToBytes(audioSamples, sampleRate, format);
            String contentType = getContentType(format);

            PutObjectRequest.Builder req = PutObjectRequest.builder()
                    .bucket(bucket)
                    .key(key)
                    .contentType(contentType);
            if (metadata != null && !metadata.isEmpty()) {
                Map<String, String> md = new HashMap<>(metadata);
                if (timestamp > 0) md.put("timestamp", String.valueOf(timestamp));
                req = req.metadata(md);
            }
            s3.putObject(req.build(), RequestBody.fromBytes(bytes));
            String s3Uri = "s3://" + bucket + "/" + key;
            logger.info("Audio saved to S3: {} ({} bytes)", s3Uri, bytes.length);
            return new SaveResult(true, s3Uri, key, bytes.length, null);
        } catch (S3Exception e) {
            String msg = e.awsErrorDetails() != null ? e.awsErrorDetails().errorCode() + ": " + e.awsErrorDetails().errorMessage() : e.getMessage();
            logger.error("S3 upload failed: {}", msg, e);
            return new SaveResult(false, null, fileName, 0, msg);
        } catch (Exception e) {
            logger.error("S3 upload failed: {}", e.getMessage(), e);
            return new SaveResult(false, null, fileName, 0, e.getMessage());
        }
    }

    private byte[] convertAudioToBytes(float[] audioSamples, int sampleRate, AudioFormat format) throws IOException {
        switch (format) {
            case WAV:
                return convertToWav(audioSamples, sampleRate);
            case RAW:
                return convertToRaw(audioSamples);
            default:
                return convertToWav(audioSamples, sampleRate);
        }
    }

    private byte[] convertToWav(float[] audioSamples, int sampleRate) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        writeWavHeader(baos, audioSamples.length, sampleRate);
        for (float sample : audioSamples) {
            short pcmSample = (short) (sample * 32767.0f);
            baos.write(pcmSample & 0xFF);
            baos.write((pcmSample >> 8) & 0xFF);
        }
        return baos.toByteArray();
    }

    private byte[] convertToRaw(float[] audioSamples) {
        byte[] rawBytes = new byte[audioSamples.length * 4];
        ByteBuffer buffer = ByteBuffer.wrap(rawBytes).order(ByteOrder.LITTLE_ENDIAN);
        for (float sample : audioSamples) buffer.putFloat(sample);
        return rawBytes;
    }

    private void writeWavHeader(ByteArrayOutputStream baos, int numSamples, int sampleRate) throws IOException {
        baos.write("RIFF".getBytes());
        writeInt(baos, 36 + numSamples * 2);
        baos.write("WAVE".getBytes());
        baos.write("fmt ".getBytes());
        writeInt(baos, 16);
        writeShort(baos, 1);
        writeShort(baos, 1);
        writeInt(baos, sampleRate);
        writeInt(baos, sampleRate * 2);
        writeShort(baos, 2);
        writeShort(baos, 16);
        baos.write("data".getBytes());
        writeInt(baos, numSamples * 2);
    }

    private void writeInt(ByteArrayOutputStream baos, int value) throws IOException {
        baos.write(value & 0xFF);
        baos.write((value >> 8) & 0xFF);
        baos.write((value >> 16) & 0xFF);
        baos.write((value >> 24) & 0xFF);
    }

    private void writeShort(ByteArrayOutputStream baos, int value) throws IOException {
        baos.write(value & 0xFF);
        baos.write((value >> 8) & 0xFF);
    }

    private String getContentType(AudioFormat format) {
        switch (format) {
            case WAV: return "audio/wav";
            case RAW: return "application/octet-stream";
            default: return "application/octet-stream";
        }
    }

    public static class SaveResult {
        public final boolean success;
        public final String s3Uri;
        public final String key;
        public final long size;
        public final String error;
        public SaveResult(boolean success, String s3Uri, String key, long size, String error) {
            this.success = success; this.s3Uri = s3Uri; this.key = key; this.size = size; this.error = error;
        }
    }
}


