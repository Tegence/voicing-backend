package org.example.voicingbackend.model;

import java.time.Instant;
import java.util.Map;

/**
 * Audio model entity for storing model information
 */
public class AudioModel {
    private String id;
    private String name;
    private String version;
    private String modelPath;
    private long modelSizeBytes;
    private String inputName;
    private String outputName;
    private int embeddingDimension;
    private int expectedSampleRate;
    private boolean normalizeAudio;
    private float confidenceThreshold;
    private Map<String, String> additionalParams;
    private Instant loadedAt;
    private boolean isLoaded;
    
    public AudioModel() {
        this.loadedAt = Instant.now();
        this.isLoaded = false;
    }
    
    public AudioModel(String name, String version, String modelPath) {
        this();
        this.name = name;
        this.version = version;
        this.modelPath = modelPath;
    }
    
    // Getters and setters
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    
    public String getVersion() { return version; }
    public void setVersion(String version) { this.version = version; }
    
    public String getModelPath() { return modelPath; }
    public void setModelPath(String modelPath) { this.modelPath = modelPath; }
    
    public long getModelSizeBytes() { return modelSizeBytes; }
    public void setModelSizeBytes(long modelSizeBytes) { this.modelSizeBytes = modelSizeBytes; }
    
    public String getInputName() { return inputName; }
    public void setInputName(String inputName) { this.inputName = inputName; }
    
    public String getOutputName() { return outputName; }
    public void setOutputName(String outputName) { this.outputName = outputName; }
    
    public int getEmbeddingDimension() { return embeddingDimension; }
    public void setEmbeddingDimension(int embeddingDimension) { this.embeddingDimension = embeddingDimension; }
    
    public int getExpectedSampleRate() { return expectedSampleRate; }
    public void setExpectedSampleRate(int expectedSampleRate) { this.expectedSampleRate = expectedSampleRate; }
    
    public boolean isNormalizeAudio() { return normalizeAudio; }
    public void setNormalizeAudio(boolean normalizeAudio) { this.normalizeAudio = normalizeAudio; }
    
    public float getConfidenceThreshold() { return confidenceThreshold; }
    public void setConfidenceThreshold(float confidenceThreshold) { this.confidenceThreshold = confidenceThreshold; }
    
    public Map<String, String> getAdditionalParams() { return additionalParams; }
    public void setAdditionalParams(Map<String, String> additionalParams) { this.additionalParams = additionalParams; }
    
    public Instant getLoadedAt() { return loadedAt; }
    public void setLoadedAt(Instant loadedAt) { this.loadedAt = loadedAt; }
    
    public boolean isLoaded() { return isLoaded; }
    public void setLoaded(boolean loaded) { isLoaded = loaded; }
    
    @Override
    public String toString() {
        return "AudioModel{" +
                "id='" + id + '\'' +
                ", name='" + name + '\'' +
                ", version='" + version + '\'' +
                ", modelPath='" + modelPath + '\'' +
                ", modelSizeBytes=" + modelSizeBytes +
                ", inputName='" + inputName + '\'' +
                ", outputName='" + outputName + '\'' +
                ", embeddingDimension=" + embeddingDimension +
                ", expectedSampleRate=" + expectedSampleRate +
                ", normalizeAudio=" + normalizeAudio +
                ", confidenceThreshold=" + confidenceThreshold +
                ", loadedAt=" + loadedAt +
                ", isLoaded=" + isLoaded +
                '}';
    }
}
