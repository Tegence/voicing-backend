package org.example.voicingbackend.service;

import org.example.voicingbackend.audiomodel.AudioFormat;
import java.util.Map;

/**
 * Common interface for audio storage backends (GCS, S3, etc.)
 */
public interface StorageService {
    StorageResult save(float[] audioSamples, int sampleRate, String username, String fileName,
                       AudioFormat format, Map<String, String> metadata, long timestamp);

    record StorageResult(boolean success, String uri, String key, long sizeBytes, String error) {}
}
