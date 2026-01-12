package org.example.voicingbackend.service;

import org.example.voicingbackend.audiomodel.*;
import org.example.voicingbackend.model.AudioModel;
import org.example.voicingbackend.util.PyTorchAudioModelLoader;
import org.example.voicingbackend.util.ForegroundBackgroundSeparator;
import org.example.voicingbackend.config.ConfigurationManager;
import org.example.voicingbackend.util.SpeakerEmbeddingExtractor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * Service layer for audio model operations
 */
public class AudioModelService {
    private static final Logger logger = LoggerFactory.getLogger(AudioModelService.class);
    
    private final PyTorchAudioModelLoader modelLoader;
    private AudioModel currentModel;
    private ForegroundBackgroundSeparator separator; // optional DJL-based separator
    private SpeakerEmbeddingExtractor embedder; // ONNX-based speaker embedder
    
    public AudioModelService() {
        this.modelLoader = new PyTorchAudioModelLoader();
        this.currentModel = null;
        logger.info("Audio model service initialized");
        // Try to load suppression model if configured
        try {
            String path = ConfigurationManager.getInstance().getString("audio.suppression.model.path", "");
            if (path != null && !path.isBlank()) {
                java.nio.file.Path modelPath = resolveModelPath(path);
                if (modelPath != null) {
                    this.separator = new ForegroundBackgroundSeparator(modelPath);
                    logger.info("Loaded suppression model from {}", modelPath);
                } else {
                    logger.warn("Suppression model not found at path or classpath: {}", path);
                }
            }
        } catch (Exception e) {
            logger.warn("Failed to load suppression model: {}", e.getMessage());
        }

        // Load speaker embedding model if configured
        try {
            String spPath = ConfigurationManager.getInstance().getString("audio.embedding.model.path", "");
            if (spPath != null && !spPath.isBlank()) {
                java.nio.file.Path modelPath = resolveModelPath(spPath);
                if (modelPath != null) {
                    this.embedder = new SpeakerEmbeddingExtractor(modelPath);
                    logger.info("Loaded speaker embedding model from {}", modelPath);
                } else {
                    logger.warn("Speaker embedding model not found at {}", spPath);
                }
            }
        } catch (Exception e) {
            logger.warn("Failed to load speaker embedding model: {}", e.getMessage());
        }
    }
    
    /**
     * Loads a PyTorch audio model
     */
    public LoadModelResult loadModel(String modelPath, ModelConfig config) {
        try {
            logger.info("Loading model from: {}", modelPath);
            
            // Convert protobuf config to internal model
            AudioModel model = new AudioModel();
            model.setModelPath(modelPath);
            
            if (config != null) {
                model.setConfidenceThreshold(config.getConfidenceThreshold());
                model.setExpectedSampleRate(config.getExpectedSampleRate());
                model.setNormalizeAudio(config.getNormalizeAudio());
                model.setAdditionalParams(config.getAdditionalParamsMap());
            }
            
            // Load the model using the loader
            boolean success = modelLoader.loadModel(modelPath);
            
            if (success) {
                // Get model info from loader
                Map<String, Object> modelInfo = modelLoader.getModelInfo();
                
                model.setName((String) modelInfo.getOrDefault("name", "Unknown"));
                model.setVersion((String) modelInfo.getOrDefault("version", "1.0"));
                model.setModelSizeBytes((Long) modelInfo.getOrDefault("size_bytes", 0L));
                model.setInputName((String) modelInfo.getOrDefault("input_name", "wave"));
                model.setOutputName((String) modelInfo.getOrDefault("output_name", "embedding"));
                model.setEmbeddingDimension((Integer) modelInfo.getOrDefault("embedding_dimension", 512));
                model.setLoaded(true);
                
                this.currentModel = model;
                
                logger.info("Model loaded successfully: {}", model.getName());
                return new LoadModelResult(true, "Model loaded successfully", convertToProtoModelInfo(model));
            } else {
                logger.error("Failed to load model from: {}", modelPath);
                return new LoadModelResult(false, "Failed to load model", null);
            }
            
        } catch (Exception e) {
            logger.error("Error loading model: {}", e.getMessage(), e);
            return new LoadModelResult(false, "Error loading model: " + e.getMessage(), null);
        }
    }
    
    /**
     * Processes audio samples to extract speaker embedding
     */
    public ProcessAudioResult processAudio(float[] audioSamples, int sampleRate, long timestamp) {
        try {
            if (currentModel == null || !currentModel.isLoaded()) {
                return new ProcessAudioResult(false, null, 0.0f, "No model loaded");
            }
            
            logger.info("Processing audio with {} samples at {}Hz", audioSamples.length, sampleRate);
            
            // Process audio using the model loader
            float[] embedding = modelLoader.processAudio(audioSamples, sampleRate);
            float confidence = modelLoader.calculateEmbeddingConfidence(embedding);
            
            if (embedding != null && embedding.length > 0) {
                logger.info("Audio processed successfully, embedding dimension: {}", embedding.length);
                return new ProcessAudioResult(true, embedding, confidence, null);
            } else {
                logger.error("Failed to process audio");
                return new ProcessAudioResult(false, null, 0.0f, "Failed to process audio");
            }
            
        } catch (Exception e) {
            logger.error("Error processing audio: {}", e.getMessage(), e);
            return new ProcessAudioResult(false, null, 0.0f, "Error processing audio: " + e.getMessage());
        }
    }
    
    /**
     * Gets information about the currently loaded model
     */
    public GetModelInfoResult getModelInfo() {
        if (currentModel != null && currentModel.isLoaded()) {
            return new GetModelInfoResult(true, convertToProtoModelInfo(currentModel));
        } else {
            return new GetModelInfoResult(false, null);
        }
    }
    
    /**
     * Suppresses background from audio samples using the loaded model.
     */
    public SuppressBackgroundResult suppressBackground(float[] audioSamples, int sampleRate, boolean returnBackground) {
        try {
            // Prefer DJL separator if available, else fallback to mock
            if (separator != null) {
                int expectedRate = ConfigurationManager.getInstance().getAudioDefaultSampleRate();
                float[] input = audioSamples;
                if (sampleRate > 0 && sampleRate != expectedRate) {
                    input = resampleLinear(audioSamples, sampleRate, expectedRate);
                }
                ForegroundBackgroundSeparator.SeparationOutput out = separator.separate(input, expectedRate, returnBackground);
                return new SuppressBackgroundResult(true, out.foreground, out.background, null);
            }
            if (currentModel == null || !currentModel.isLoaded()) {
                return new SuppressBackgroundResult(false, null, null, "Suppression model not loaded");
            }
            PyTorchAudioModelLoader.SuppressionOutput out = modelLoader.suppressBackground(audioSamples, sampleRate, returnBackground);
            if (out == null || out.foreground == null) {
                return new SuppressBackgroundResult(false, null, null, "Suppression failed");
            }
            return new SuppressBackgroundResult(true, out.foreground, out.background, null);
        } catch (Exception e) {
            logger.error("Error suppressing background: {}", e.getMessage(), e);
            return new SuppressBackgroundResult(false, null, null, "Error: " + e.getMessage());
        }
    }

    public VerifyResult verifyUser(String userId, float[] audio, int sampleRate) {
        try {
            if (embedder == null) return new VerifyResult(false, 0f, 0f, false, "Embedding model not loaded");
            if (userId == null || userId.isBlank()) return new VerifyResult(false, 0f, 0f, false, "userId required");
            // Build single embedding from speech-only audio
            float[] speechOnly = concatenateSpeech(audio, sampleRate);
            if (speechOnly == null || speechOnly.length == 0) {
                return new VerifyResult(false, 0f, 0f, false, "No speech detected");
            }
            float[] query = embedder.embed(speechOnly, true);
            // Fetch reference embeddings for user
            org.example.voicingbackend.repository.impl.MongoEmbeddingRepository repo = new org.example.voicingbackend.repository.impl.MongoEmbeddingRepository();
            java.util.List<float[]> refs = repo.findByUserId(userId, 100);
            if (refs.isEmpty()) return new VerifyResult(false, 0f, 0f, false, "No reference embeddings for user");
            float maxSim = -1f;
            for (float[] r : refs) {
                float s = cosine(query, r);
                if (s > maxSim) maxSim = s;
            }
            double clamped = Math.max(0.0, Math.min(1.0, maxSim));
            float percentage = (float) (clamped * 100.0);
            boolean pass = percentage >= ConfigurationManager.getInstance().getVerificationThresholdPercent();
            return new VerifyResult(true, maxSim, percentage, pass, null);
        } catch (Exception e) {
            logger.error("verifyUser failed: {}", e.getMessage(), e);
            return new VerifyResult(false, 0f, 0f, false, e.getMessage());
        }
    }

    private float cosine(float[] a, float[] b) {
        int n = Math.min(a.length, b.length);
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < n; i++) { dot += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i]; }
        if (na == 0 || nb == 0) return 0f;
        return (float) (dot / (Math.sqrt(na) * Math.sqrt(nb)));
    }

    public static class VerifyResult {
        public final boolean success;
        public final float score;
        public final float percentage;
        public final boolean verified;
        public final String error;
        public VerifyResult(boolean success, float score, float percentage, boolean verified, String error) {
            this.success = success; this.score = score; this.percentage = percentage; this.verified = verified; this.error = error;
        }
    }
    public ExtractEmbeddingsResult extractEmbeddings(float[] audio, int sampleRate) {
        try {
            if (embedder == null) {
                return new ExtractEmbeddingsResult(false, null, "Embedding model not loaded");
            }
            int windowMs = 500;
            int hopMs = 250;
            int winSamples = (int) Math.round(sampleRate * (windowMs / 1000.0));
            int hopSamples = (int) Math.round(sampleRate * (hopMs / 1000.0));
            if (winSamples <= 0 || hopSamples <= 0 || audio == null || audio.length < winSamples) {
                return new ExtractEmbeddingsResult(false, java.util.Collections.emptyList(), "Audio too short");
            }
            double vadThreshDb = ConfigurationManager.getInstance().getInt("audio.vad.threshold.db", -40);
            java.util.List<EmbeddingWindow> out = new java.util.ArrayList<>();
            for (int start = 0; start + winSamples <= audio.length; start += hopSamples) {
                int end = start + winSamples;
                float[] chunk = java.util.Arrays.copyOfRange(audio, start, end);
                if (!isSpeech(chunk, vadThreshDb)) continue;
                float[] emb = embedder.embed(chunk, true);
                long startMs = Math.round(start * 1000.0 / sampleRate);
                long endMs = Math.round(end * 1000.0 / sampleRate);
                out.add(new EmbeddingWindow(startMs, endMs, emb));
            }
            return new ExtractEmbeddingsResult(true, out, null);
        } catch (Exception e) {
            logger.error("Embedding extraction failed: {}", e.getMessage(), e);
            return new ExtractEmbeddingsResult(false, null, e.getMessage());
        }
    }

    private boolean isSpeech(float[] chunk, double threshDb) {
        double sum = 0.0;
        for (float v : chunk) sum += v * v;
        double rms = Math.sqrt(sum / Math.max(1, chunk.length));
        double db = 20.0 * Math.log10(Math.max(1e-9, rms));
        return db >= threshDb;
    }

    public static class EmbeddingWindow {
        public final long startMs;
        public final long endMs;
        public final float[] vector;
        public EmbeddingWindow(long startMs, long endMs, float[] vector) {
            this.startMs = startMs; this.endMs = endMs; this.vector = vector;
        }
    }

    public static class ExtractEmbeddingsResult {
        public final boolean success;
        public final java.util.List<EmbeddingWindow> windows;
        public final String error;
        public ExtractEmbeddingsResult(boolean success, java.util.List<EmbeddingWindow> windows, String error) {
            this.success = success; this.windows = windows; this.error = error;
        }
    }

    /**
     * Variable-length speech segment extracted via simple energy VAD.
     */
    public static class SpeechSegment {
        public final long startMs;
        public final long endMs;
        public final float[] samples;
        public SpeechSegment(long startMs, long endMs, float[] samples) {
            this.startMs = startMs; this.endMs = endMs; this.samples = samples;
        }
    }

    /**
     * Detects variable-length speech segments using frame-based VAD and merges contiguous voiced frames.
     * Configuration keys (with defaults):
     *  - audio.vad.threshold.db: -40
     *  - audio.vad.frame.ms: 30
     *  - audio.vad.hop.ms: 15
     *  - audio.vad.segment.min.ms: 300
     *  - audio.vad.max.silence.bridge.ms: 150
     */
    public java.util.List<SpeechSegment> detectSpeechSegments(float[] audio, int sampleRate) {
        java.util.List<SpeechSegment> segments = new java.util.ArrayList<>();
        if (audio == null || audio.length == 0 || sampleRate <= 0) {
            return segments;
        }

        org.example.voicingbackend.config.ConfigurationManager cfg = ConfigurationManager.getInstance();
        double threshDb = cfg.getInt("audio.vad.threshold.db", -40);
        int frameMs = cfg.getInt("audio.vad.frame.ms", 30);
        int hopMs = cfg.getInt("audio.vad.hop.ms", 15);
        int minSegMs = cfg.getInt("audio.vad.segment.min.ms", 300);
        int bridgeMs = cfg.getInt("audio.vad.max.silence.bridge.ms", 150);

        int frameSamples = Math.max(1, (int) Math.round(sampleRate * (frameMs / 1000.0)));
        int hopSamples = Math.max(1, (int) Math.round(sampleRate * (hopMs / 1000.0)));
        int minSegSamples = Math.max(1, (int) Math.round(sampleRate * (minSegMs / 1000.0)));
        int bridgeSamples = Math.max(0, (int) Math.round(sampleRate * (bridgeMs / 1000.0)));

        // Frame-wise VAD
        java.util.List<Boolean> voiced = new java.util.ArrayList<>();
        java.util.List<Integer> frameStartSamples = new java.util.ArrayList<>();
        for (int start = 0; start + frameSamples <= audio.length; start += hopSamples) {
            int end = start + frameSamples;
            float[] chunk = java.util.Arrays.copyOfRange(audio, start, end);
            boolean sp = isSpeech(chunk, threshDb);
            voiced.add(sp);
            frameStartSamples.add(start);
        }

        // Build raw segments from contiguous voiced frames
        java.util.List<int[]> rawSegs = new java.util.ArrayList<>(); // [startSample, endSample)
        int currentStart = -1;
        int lastEnd = -1;
        for (int i = 0; i < voiced.size(); i++) {
            boolean sp = voiced.get(i);
            int startSample = frameStartSamples.get(i);
            int endSample = Math.min(audio.length, startSample + frameSamples);
            if (sp) {
                if (currentStart < 0) {
                    currentStart = startSample;
                }
                lastEnd = endSample;
            } else {
                if (currentStart >= 0) {
                    rawSegs.add(new int[]{currentStart, lastEnd});
                    currentStart = -1;
                }
            }
        }
        if (currentStart >= 0) {
            rawSegs.add(new int[]{currentStart, Math.max(lastEnd, currentStart)});
        }

        // Merge close segments separated by short silence (bridgeSamples)
        java.util.List<int[]> merged = new java.util.ArrayList<>();
        for (int i = 0; i < rawSegs.size(); i++) {
            int[] seg = rawSegs.get(i);
            if (merged.isEmpty()) {
                merged.add(seg);
                continue;
            }
            int[] prev = merged.get(merged.size() - 1);
            if (seg[0] - prev[1] <= bridgeSamples) {
                // Extend previous
                prev[1] = Math.max(prev[1], seg[1]);
            } else {
                merged.add(seg);
            }
        }

        // Filter by min duration and emit
        for (int[] m : merged) {
            int len = m[1] - m[0];
            if (len >= minSegSamples) {
                float[] samples = java.util.Arrays.copyOfRange(audio, m[0], m[1]);
                long startMs = Math.round(m[0] * 1000.0 / sampleRate);
                long endMs = Math.round(m[1] * 1000.0 / sampleRate);
                segments.add(new SpeechSegment(startMs, endMs, samples));
            }
        }
        return segments;
    }

    /**
     * Concatenate all detected speech segments into a single waveform.
     */
    public float[] concatenateSpeech(float[] audio, int sampleRate) {
        java.util.List<SpeechSegment> segs = detectSpeechSegments(audio, sampleRate);
        if (segs == null || segs.isEmpty()) return new float[0];
        int total = 0;
        for (SpeechSegment s : segs) total += s.samples.length;
        float[] out = new float[total];
        int pos = 0;
        for (SpeechSegment s : segs) {
            System.arraycopy(s.samples, 0, out, pos, s.samples.length);
            pos += s.samples.length;
        }
        return out;
    }
    private java.nio.file.Path resolveModelPath(String configuredPath) {
        try {
            java.nio.file.Path p = java.nio.file.Paths.get(configuredPath);
            if (java.nio.file.Files.exists(p)) {
                return p;
            }
        } catch (Exception ignore) { }
        // Try classpath
        try (java.io.InputStream is = getClass().getClassLoader().getResourceAsStream(configuredPath)) {
            if (is != null) {
                String lower = configuredPath.toLowerCase(java.util.Locale.ROOT);
                String suffix = lower.endsWith(".onnx") ? ".onnx" : (lower.endsWith(".pth") ? ".pth" : (lower.endsWith(".pt") ? ".pt" : ""));
                if (suffix.isEmpty()) suffix = ".bin";
                java.nio.file.Path tmp = java.nio.file.Files.createTempFile("model", suffix);
                java.nio.file.Files.copy(is, tmp, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                tmp.toFile().deleteOnExit();
                return tmp;
            }
        } catch (Exception e) {
            logger.warn("Failed to load suppression model from classpath {}: {}", configuredPath, e.getMessage());
        }
        return null;
    }

    private float[] resampleLinear(float[] input, int fromRate, int toRate) {
        if (fromRate <= 0 || toRate <= 0 || fromRate == toRate || input == null || input.length == 0) {
            return input;
        }
        int newLen = Math.max(1, (int) Math.round(input.length * (toRate / (double) fromRate)));
        float[] out = new float[newLen];
        for (int i = 0; i < newLen; i++) {
            double pos = i * (fromRate / (double) toRate);
            int i0 = (int) Math.floor(pos);
            int i1 = Math.min(input.length - 1, i0 + 1);
            double t = pos - i0;
            out[i] = (float) ((1 - t) * input[i0] + t * input[i1]);
        }
        return out;
    }
    
    /**
     * Unloads the current model
     */
    public UnloadModelResult unloadModel() {
        try {
            if (currentModel != null && currentModel.isLoaded()) {
                modelLoader.unloadModel();
                currentModel.setLoaded(false);
                currentModel = null;
                
                logger.info("Model unloaded successfully");
                return new UnloadModelResult(true, "Model unloaded successfully");
            } else {
                return new UnloadModelResult(false, "No model currently loaded");
            }
        } catch (Exception e) {
            logger.error("Error unloading model: {}", e.getMessage(), e);
            return new UnloadModelResult(false, "Error unloading model: " + e.getMessage());
        }
    }
    
    /**
     * Converts internal AudioModel to protobuf ModelInfo
     */
    private ModelInfo convertToProtoModelInfo(AudioModel model) {
        ModelInfo.Builder builder = ModelInfo.newBuilder()
            .setModelName(model.getName())
            .setModelVersion(model.getVersion())
            .setModelPath(model.getModelPath())
            .setModelSizeBytes(model.getModelSizeBytes())
            .setInputName(model.getInputName())
            .setOutputName(model.getOutputName())
            .setEmbeddingDimension(model.getEmbeddingDimension());
        
        // Add supported features
        builder.addSupportedFeatures("speaker_embedding");
        builder.addSupportedFeatures("audio_processing");
        
        // Add configuration
        ModelConfig.Builder configBuilder = ModelConfig.newBuilder()
            .setConfidenceThreshold(model.getConfidenceThreshold())
            .setExpectedSampleRate(model.getExpectedSampleRate())
            .setNormalizeAudio(model.isNormalizeAudio());
        
        // Add additional parameters
        if (model.getAdditionalParams() != null) {
            configBuilder.putAllAdditionalParams(model.getAdditionalParams());
        }
        
        builder.setConfig(configBuilder.build());
        
        return builder.build();
    }
    
    // Result classes
    
    public static class LoadModelResult {
        private final boolean success;
        private final String message;
        private final ModelInfo modelInfo;
        
        public LoadModelResult(boolean success, String message, ModelInfo modelInfo) {
            this.success = success;
            this.message = message;
            this.modelInfo = modelInfo;
        }
        
        public boolean isSuccess() { return success; }
        public String getMessage() { return message; }
        public ModelInfo getModelInfo() { return modelInfo; }
    }
    
    public static class ProcessAudioResult {
        private final boolean success;
        private final float[] embedding;
        private final float confidence;
        private final String errorMessage;
        
        public ProcessAudioResult(boolean success, float[] embedding, float confidence, String errorMessage) {
            this.success = success;
            this.embedding = embedding;
            this.confidence = confidence;
            this.errorMessage = errorMessage;
        }
        
        public boolean isSuccess() { return success; }
        public float[] getEmbedding() { return embedding; }
        public float getConfidence() { return confidence; }
        public String getErrorMessage() { return errorMessage; }
    }
    
    public static class GetModelInfoResult {
        private final boolean modelLoaded;
        private final ModelInfo modelInfo;
        
        public GetModelInfoResult(boolean modelLoaded, ModelInfo modelInfo) {
            this.modelLoaded = modelLoaded;
            this.modelInfo = modelInfo;
        }
        
        public boolean isModelLoaded() { return modelLoaded; }
        public ModelInfo getModelInfo() { return modelInfo; }
    }
    
    public static class UnloadModelResult {
        private final boolean success;
        private final String message;
        
        public UnloadModelResult(boolean success, String message) {
            this.success = success;
            this.message = message;
        }
        
        public boolean isSuccess() { return success; }
        public String getMessage() { return message; }
    }

    public static class SuppressBackgroundResult {
        private final boolean success;
        private final float[] foreground;
        private final float[] background;
        private final String errorMessage;

        public SuppressBackgroundResult(boolean success, float[] foreground, float[] background, String errorMessage) {
            this.success = success;
            this.foreground = foreground;
            this.background = background;
            this.errorMessage = errorMessage;
        }
        public boolean isSuccess() { return success; }
        public float[] getForeground() { return foreground; }
        public float[] getBackground() { return background; }
        public String getErrorMessage() { return errorMessage; }
    }

    public static class SingleEmbeddingResult {
        public final boolean success;
        public final float[] embedding;
        public final long durationMs;
        public final String error;
        public SingleEmbeddingResult(boolean success, float[] embedding, long durationMs, String error) {
            this.success = success; this.embedding = embedding; this.durationMs = durationMs; this.error = error;
        }
    }

    /**
     * Compute a single utterance-level embedding by removing silence and stitching voiced segments.
     */
    public SingleEmbeddingResult computeSingleEmbedding(float[] audio, int sampleRate) {
        try {
            if (embedder == null) return new SingleEmbeddingResult(false, null, 0, "Embedding model not loaded");
            float[] speechOnly = concatenateSpeech(audio, sampleRate);
            if (speechOnly == null || speechOnly.length == 0) {
                return new SingleEmbeddingResult(false, null, 0, "No speech detected");
            }
            // If model expects fixed windows, compute mean embedding across windows of speechOnly
            int windowMs = 500;
            int hopMs = 250;
            int winSamples = Math.max(1, (int) Math.round(sampleRate * (windowMs / 1000.0)));
            int hopSamples = Math.max(1, (int) Math.round(sampleRate * (hopMs / 1000.0)));
            float[] emb;
            if (speechOnly.length >= winSamples) {
                java.util.List<float[]> embs = new java.util.ArrayList<>();
                for (int start = 0; start + winSamples <= speechOnly.length; start += hopSamples) {
                    float[] chunk = java.util.Arrays.copyOfRange(speechOnly, start, start + winSamples);
                    embs.add(embedder.embed(chunk, true));
                }
                if (embs.isEmpty()) {
                    emb = embedder.embed(speechOnly, true);
                } else {
                    int dim = embs.get(0).length;
                    double[] sum = new double[dim];
                    for (float[] e : embs) {
                        for (int i = 0; i < dim; i++) sum[i] += e[i];
                    }
                    emb = new float[dim];
                    for (int i = 0; i < dim; i++) emb[i] = (float) (sum[i] / embs.size());
                    // L2 normalize
                    double nrm = 0.0;
                    for (float v : emb) nrm += v * v;
                    nrm = Math.sqrt(Math.max(1e-12, nrm));
                    for (int i = 0; i < emb.length; i++) emb[i] /= (float) nrm;
                }
            } else {
                emb = embedder.embed(speechOnly, true);
            }
            long durationMs = Math.round(speechOnly.length * 1000.0 / Math.max(1, sampleRate));
            return new SingleEmbeddingResult(true, emb, durationMs, null);
        } catch (Exception e) {
            logger.error("computeSingleEmbedding failed: {}", e.getMessage(), e);
            return new SingleEmbeddingResult(false, null, 0, e.getMessage());
        }
    }
}
