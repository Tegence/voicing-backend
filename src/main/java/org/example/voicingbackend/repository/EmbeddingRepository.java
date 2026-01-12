package org.example.voicingbackend.repository;

import java.util.List;

public interface EmbeddingRepository {
    void save(String userId, String audioId, long startMs, long endMs, String modelVersion, float[] vector);
    List<float[]> findByUserId(String userId, int limit);
}


