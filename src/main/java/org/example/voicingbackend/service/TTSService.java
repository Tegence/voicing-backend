package org.example.voicingbackend.service;

import ai.djl.Model;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.NDManager;
import ai.djl.ndarray.types.DataType;
import ai.djl.translate.Translator;
import ai.djl.translate.TranslatorContext;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OnnxValue;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import ai.onnxruntime.OrtSession.SessionOptions;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.voicingbackend.config.ConfigurationManager;
import org.example.voicingbackend.util.PythonTtsClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * FastSpeech2 (TorchScript traced) + HiFiGAN (ONNX) text-to-speech pipeline.
 * Inputs: phoneme ids (int64) -> mel (float32 [1,80,T]) -> waveform (float32 [1,1,N]).
 */
public class TTSService {
    private static final Logger logger = LoggerFactory.getLogger(TTSService.class);
    private static final int FIXED_SEQUENCE_LENGTH = 50;

    private final TextToPhonemeService g2p;
    private final TextToPhonemeService.PhonemeFormat phonemeFormat;

    private String fastspeechPath;
    private String fastspeechOnnxPath;
    private String hifiganPath;
    private int defaultSampleRate;

    // Baked ONNX (direct waveform) configuration
    private String bakedOnnxPath;
    private String bakedVocabResource;
    private int bakedSampleRate;
    private volatile java.util.Map<String, Integer> ipaVocab;
    private volatile java.util.Map<String, Integer> vitsVocab;

    // Vits ONNX
    private String vitsOnnxPath;
    private String vitsVocabResources;
    private final PythonTtsClient ttsClient;

    /**
     * Creates a TTSService with injected dependencies.
     *
     * @param g2p       an externally managed TextToPhonemeService
     * @param ttsClient an externally managed PythonTtsClient
     */
    public TTSService(TextToPhonemeService g2p, PythonTtsClient ttsClient) {
        this.g2p = g2p;
        this.ttsClient = ttsClient;
        this.phonemeFormat = TextToPhonemeService.PhonemeFormat.ARPABET;
        initConfig();
    }

    /**
     * @deprecated Use {@link #TTSService(TextToPhonemeService, PythonTtsClient)} for dependency injection.
     */
    @Deprecated
    public TTSService() {
        this.g2p = new TextToPhonemeService();
        this.ttsClient = new PythonTtsClient();
        this.phonemeFormat = TextToPhonemeService.PhonemeFormat.ARPABET;
        initConfig();
    }

    /**
     * Loads TTS model paths and sample-rate settings from ConfigurationManager.
     * Called by all constructors.
     */
    private void initConfig() {
        ConfigurationManager cfg = ConfigurationManager.getInstance();
        this.fastspeechPath = cfg.getString("tts.fastspeech.path", "src/main/resources/fastspeech2_traced.pt");
        this.fastspeechOnnxPath = cfg.getString("tts.fastspeech.onnx.path", "");
        this.hifiganPath = cfg.getString("tts.hifigan.path", "src/main/resources/hifigan_ljspeech.onnx");
        this.vitsOnnxPath = cfg.getString("tts.vits.onnx.path", "src/main/resources/vits_model.onnx");
        this.defaultSampleRate = cfg.getInt("tts.sample.rate", 22050);

        // Baked model defaults
        this.bakedOnnxPath = cfg.getString("tts.baked.onnx.path", "src/main/resources/baked_model.onnx");
        this.bakedVocabResource = cfg.getString("tts.baked.vocab.resource", "config_vocab_ipa.json");
        this.bakedSampleRate = 24000; // fixed by model spec

        // Vits model
        this.vitsVocabResources = cfg.getString("tts.vits.vocab.resource", "vits_vocab.json");
    }

    public static class Result {
        public final boolean success;
        public final float[] audio;
        public final int sampleRate;
        public final String error;

        public Result(boolean success, float[] audio, int sampleRate, String error) {
            this.success = success;
            this.audio = audio;
            this.sampleRate = sampleRate;
            this.error = error;
        }
    }

    public Result synthesize(String text, int sampleRate) {
        try {
            boolean tryBaked = bakedOnnxAvailable();
            boolean tryVits = vitsOnnxAvailable();
            int sr = sampleRate > 0 ? sampleRate : (tryBaked ? bakedSampleRate : defaultSampleRate);

            if (tryVits) {
                try {
                    long[] inputIds = ttsClient.fetchTokenIdsFromGrpc(text);

                    if (inputIds != null && inputIds.length > 0) {

                        float[] wav = runVitsOnnx(inputIds);

                        if (wav != null && wav.length > 0) {
                            return new Result(true, wav, defaultSampleRate, null);
                        }
                    }

                    logger.warn("VITS inference failed, falling back");

                } catch (Exception e) {

                    logger.warn("VITS failed: {}, falling back", e.getMessage());

                }

            }

            // Prefer baked ONNX model if available
            if (tryBaked) {
                var ipaRes = g2p.convert(text, null, TextToPhonemeService.PhonemeFormat.IPA);
                if (!ipaRes.success || ipaRes.flattened == null || ipaRes.flattened.isEmpty()) {
                    logger.warn("IPA conversion failed, falling back to FS2 path: {}", ipaRes.error);
                } else {
                    long[] inputIds = buildIpaInputIds(ipaRes.flattened);
                    if (inputIds != null && inputIds.length > 2) { // must contain BOS/EOS at least
                        float[] wav = runBakedOnnx(inputIds);
                        if (wav != null && wav.length > 0) {
                            return new Result(true, wav, sr, null);
                        } else {
                            logger.warn("Baked ONNX inference returned null/empty waveform, falling back to FS2");
                        }
                    } else {
                        logger.warn("Baked input ids empty, falling back to FS2");
                    }
                }
            }

            // Fallback to FastSpeech2 (+ HiFiGAN)
            var g2pres = g2p.convert(text, null, phonemeFormat);
            if (!g2pres.success || g2pres.flattenedIds == null || g2pres.flattenedIds.isEmpty()) {
                return new Result(false, null, sr, g2pres.error != null ? g2pres.error : "G2P failed");
            }
            long[] tokens = g2pres.flattenedIds.stream().mapToLong(Integer::longValue).toArray();

            // Prefer FastSpeech2 ONNX (Paddle) if configured, else fallback to traced TorchScript
            float[][][] mel = null;
            if (fastspeechOnnxPath != null && !fastspeechOnnxPath.isBlank()) {
                mel = runFastSpeechOnnx(tokens);
                if (mel == null) logger.warn("FastSpeech2 ONNX failed, falling back to traced model");
            }
            if (mel == null) {
                mel = runFastSpeech(tokens);
            }
            if (mel == null) return new Result(false, null, sr, "FastSpeech2 failed");

            // Run HiFiGAN ONNX
            float[] wav = runHiFiGAN(mel);
            if (wav == null) return new Result(false, null, sr, "HiFiGAN failed");

            return new Result(true, wav, sr, null);
        } catch (Exception e) {
            logger.error("TTS synthesize failed: {}", e.getMessage(), e);
            return new Result(false, null, sampleRate > 0 ? sampleRate : defaultSampleRate, e.getMessage());
        }
    }

    private boolean bakedOnnxAvailable() {
        try {
            if (bakedOnnxPath == null || bakedOnnxPath.isBlank()) return false;
            java.nio.file.Path p = resolvePathOrClasspath(bakedOnnxPath, ".onnx");
            return p != null && java.nio.file.Files.exists(p);
        } catch (Exception ignore) {
            return false;
        }
    }

    private boolean vitsOnnxAvailable() {
        try {
            if (vitsOnnxPath == null || vitsOnnxPath.isBlank()) return false;
            java.nio.file.Path p = resolvePathOrClasspath(vitsOnnxPath, ".onnx");
            return p != null && java.nio.file.Files.exists(p);
        } catch (Exception ignore) {
            return false;
        }
    }

    private void ensureIpaVocabLoaded() {
        if (ipaVocab != null) return;
        synchronized (this) {
            if (ipaVocab != null) return;
            java.util.Map<String, Integer> map = new java.util.HashMap<>();
            try (java.io.InputStream is = getClass().getClassLoader().getResourceAsStream(bakedVocabResource)) {
                if (is == null) {
                    logger.warn("IPA vocab resource not found: {}", bakedVocabResource);
                } else {
                    ObjectMapper om = new ObjectMapper();
                    map = om.readValue(is, new TypeReference<java.util.Map<String, Integer>>() {
                    });
                    logger.info("Loaded IPA vocab ({} entries) from {}", map.size(), bakedVocabResource);
                }
            } catch (Exception e) {
                logger.warn("Failed to load IPA vocab {}: {}", bakedVocabResource, e.getMessage());
            }
            this.ipaVocab = map;
        }
    }

    private void ensureVitsVocabLoaded() {
        if (vitsVocab != null) return;

        synchronized (this) {
            if (vitsVocab != null) return;

            try (java.io.InputStream is = getClass().getClassLoader().getResourceAsStream(vitsVocabResources)) {

                ObjectMapper mapper = new ObjectMapper();
                vitsVocab = mapper.readValue(is, new TypeReference<java.util.Map<String, Integer>>() {
                });

                logger.info("Loaded VITS vocab size: {}", vitsVocab.size());

            } catch (Exception e) {
                throw new RuntimeException("Failed to load VITS vocab", e);
            }
        }
    }

    private long[] buildIpaInputIds(java.util.List<String> flattenedIpaTokens) {
        ensureIpaVocabLoaded();
        if (ipaVocab == null || ipaVocab.isEmpty()) return null;
        // Join tokens into a single IPA string, then map per Unicode code point to ids
        String ipa = String.join("", flattenedIpaTokens);
        java.util.List<Long> ids = new java.util.ArrayList<>();
        ids.add(0L); // BOS
        // Max effective phoneme length ≈ 510 (before BOS/EOS)
        int maxEffective = 510;
        int count = 0;
        int len = ipa.codePointCount(0, ipa.length());
        for (int i = 0; i < len && count < maxEffective; i++) {
            int cp = ipa.codePointAt(ipa.offsetByCodePoints(0, i));
            String ch = new String(Character.toChars(cp));
            Integer id = ipaVocab.get(ch);
            if (id == null) {
                // Fallback: try normalized NFC/NFD single-char variants
                String nfc = java.text.Normalizer.normalize(ch, java.text.Normalizer.Form.NFC);
                String nfd = java.text.Normalizer.normalize(ch, java.text.Normalizer.Form.NFD);
                id = ipaVocab.get(nfc);
                if (id == null) id = ipaVocab.get(nfd);
            }
            if (id == null) {
                // Unknowns map to 0 (will be effectively skipped between BOS/EOS)
                id = 0;
            }
            ids.add(id.longValue());
            count++;
        }
        ids.add(0L); // EOS
        long[] arr = new long[ids.size()];
        for (int i = 0; i < ids.size(); i++) arr[i] = ids.get(i);
        return arr;
    }

    private long[] buildVitsInputIds(java.util.List<String> phonemes) {

        ensureVitsVocabLoaded();

        java.util.List<Long> ids = new java.util.ArrayList<>();

        int blankId = vitsVocab.get("_");

        for (int i = 0; i < phonemes.size(); i++) {

            String ph = phonemes.get(i);

            Integer id = vitsVocab.get(ph);

            if (id == null)
                continue;

            ids.add(id.longValue());

            // insert blank between phonemes
            if (i < phonemes.size() - 1)
                ids.add((long) blankId);
        }

        long[] result = new long[ids.size()];

        for (int i = 0; i < ids.size(); i++)
            result[i] = ids.get(i);

        return result;
    }

    private float[] runBakedOnnx(long[] inputIds) throws Exception {
        java.nio.file.Path modelPath = resolvePathOrClasspath(bakedOnnxPath, ".onnx");
        java.nio.file.Path finalPath = modelPath != null ? modelPath : java.nio.file.Paths.get(bakedOnnxPath);
        try (OrtEnvironment env = OrtEnvironment.getEnvironment();
             SessionOptions opts = new SessionOptions();
             OrtSession session = env.createSession(finalPath.toString(), opts)) {
            java.util.Map<String, OnnxTensor> feeds = new java.util.HashMap<>();
            java.util.Set<String> inputNames = session.getInputNames();

            // input_ids [1, T]
            if (!inputNames.contains("input_ids")) {
                throw new IllegalStateException("ONNX model missing required input 'input_ids'");
            }
            long[][] ids2d = new long[1][inputIds.length];
            System.arraycopy(inputIds, 0, ids2d[0], 0, inputIds.length);
            feeds.put("input_ids", OnnxTensor.createTensor(env, ids2d));

            // voice_id [1] (default 0 if present)
            if (inputNames.contains("voice_id")) {
                long[] voice = new long[]{0L};
                feeds.put("voice_id", OnnxTensor.createTensor(env, voice));
            }
            // speed [1] (optional default 1.0)
            if (inputNames.contains("speed")) {
                float[] sp = new float[]{1.0f};
                feeds.put("speed", OnnxTensor.createTensor(env, sp));
            }

            try (OrtSession.Result result = session.run(feeds)) {
                OnnxValue wavVal = result.get(0);
                if (!(wavVal instanceof OnnxTensor)) {
                    throw new IllegalStateException("waveform output is not a tensor");
                }
                OnnxTensor t = (OnnxTensor) wavVal;
                long[] shape = t.getInfo().getShape();
                if (shape.length == 1) {
                    return (float[]) t.getValue();
                }
                if (shape.length == 2 && shape[0] == 1) {
                    float[][] y = (float[][]) t.getValue();
                    return y[0];
                }
                // Fallback: flatten
                float[] buf = t.getFloatBuffer().array();
                return buf;
            }
        }
    }

    private float[][][] runFastSpeech(long[] tokens) {
        long[] padded = new long[FIXED_SEQUENCE_LENGTH];
        java.util.Arrays.fill(padded, 0L);
        int copy = Math.min(tokens.length, FIXED_SEQUENCE_LENGTH);
        System.arraycopy(tokens, 0, padded, 0, copy);
        try (Model model = Model.newInstance("fastspeech2", "PyTorch");
             NDManager manager = NDManager.newBaseManager()) {
            java.nio.file.Path modelPath = resolvePathOrClasspath(fastspeechPath, ".pt");
            model.load(modelPath != null ? modelPath : java.nio.file.Paths.get(fastspeechPath));
            Translator<NDList, NDList> translator = new Translator<NDList, NDList>() {
                @Override
                public NDList processInput(TranslatorContext ctx, NDList input) {
                    return input;
                }

                @Override
                public NDList processOutput(TranslatorContext ctx, NDList list) {
                    return list;
                }

                @Override
                public ai.djl.translate.Batchifier getBatchifier() {
                    return null;
                }
            };
            try (ai.djl.inference.Predictor<NDList, NDList> predictor = model.newPredictor(translator)) {
                NDArray tok = manager.create(padded).reshape(1, FIXED_SEQUENCE_LENGTH).toType(DataType.INT64, false);
                NDList outList = predictor.predict(new NDList(tok));
                NDArray mel = outList.get(0);
                if (mel.getShape().dimension() < 3) {
                    logger.warn("FastSpeech2 output shape unexpected: {}", mel.getShape());
                    return new float[][][]{new float[80][100]};
                }
                long b = mel.getShape().get(0);
                long c = mel.getShape().get(1);
                long t = mel.getShape().get(2);
                float[][][] out = new float[(int) b][(int) c][(int) t];
                float[] flat = mel.toFloatArray();
                int idx = 0;
                for (int bi = 0; bi < b; bi++)
                    for (int ci = 0; ci < c; ci++)
                        for (int ti = 0; ti < t; ti++) out[bi][ci][ti] = flat[idx++];
                return out;
            }
        } catch (Exception e) {
            logger.warn("FastSpeech2 failed: {}", e.getMessage());
            return new float[][][]{new float[80][100]};
        }
    }

    /**
     * Run FastSpeech2 ONNX (PaddleSpeech). Accepts variable-length token ids and
     * normalizes output to [1, 80, T]. Supports common provider shapes: [T,80], [1,80,T], [1,T,80].
     */
    private float[][][] runFastSpeechOnnx(long[] tokens) {
        try (Model model = Model.newInstance("fastspeech2-onnx", "OnnxRuntime");
             NDManager manager = NDManager.newBaseManager()) {
            java.nio.file.Path modelPath = resolvePathOrClasspath(fastspeechOnnxPath, ".onnx");
            if (modelPath == null) {
                logger.warn("FastSpeech2 ONNX model not found at {}", fastspeechOnnxPath);
                return null;
            }
            model.load(modelPath);
            Translator<NDList, NDList> translator = new Translator<NDList, NDList>() {
                @Override
                public NDList processInput(TranslatorContext ctx, NDList input) {
                    return input;
                }

                @Override
                public NDList processOutput(TranslatorContext ctx, NDList list) {
                    return list;
                }

                @Override
                public ai.djl.translate.Batchifier getBatchifier() {
                    return null;
                }
            };
            try (ai.djl.inference.Predictor<NDList, NDList> predictor = model.newPredictor(translator)) {
                NDArray tokFlat = manager.create(tokens).toType(DataType.INT64, false); // [T]
                NDList outList;
                try {
                    outList = predictor.predict(new NDList(tokFlat)); // prefer [T]
                } catch (Exception e) {
                    // Retry with [1, T]
                    NDArray tok = tokFlat.reshape(1, tokens.length);
                    outList = predictor.predict(new NDList(tok));
                }
                NDArray mel = outList.get(0);
                int dims = (int) mel.getShape().dimension();
                float[] flat = mel.toFloatArray();
                if (dims == 2) {
                    long d0 = mel.getShape().get(0);
                    long d1 = mel.getShape().get(1);
                    if (d1 == 80) { // [T,80] -> [1,80,T]
                        int T = (int) d0;
                        float[][][] out = new float[1][80][T];
                        int idx = 0;
                        for (int t = 0; t < T; t++) {
                            for (int c = 0; c < 80; c++) out[0][c][t] = flat[idx++];
                        }
                        return out;
                    } else if (d0 == 80) { // [80,T] -> [1,80,T]
                        int T = (int) d1;
                        float[][][] out = new float[1][80][T];
                        int idx = 0;
                        for (int c = 0; c < 80; c++) {
                            for (int t = 0; t < T; t++) out[0][c][t] = flat[idx++];
                        }
                        return out;
                    } else {
                        logger.warn("Unexpected 2D FS2 ONNX shape: {}", mel.getShape());
                        return new float[][][]{new float[80][100]};
                    }
                } else if (dims == 3) {
                    long d0 = mel.getShape().get(0);
                    long d1 = mel.getShape().get(1);
                    long d2 = mel.getShape().get(2);
                    if (d0 == 1 && d1 == 80) { // [1,80,T]
                        int T = (int) d2;
                        float[][][] out = new float[1][80][T];
                        int idx = 0;
                        for (int c = 0; c < 80; c++)
                            for (int t = 0; t < T; t++) out[0][c][t] = flat[idx++];
                        return out;
                    } else if (d0 == 1 && d2 == 80) { // [1,T,80] -> transpose
                        int T = (int) d1;
                        float[][][] out = new float[1][80][T];
                        int idx = 0;
                        for (int t = 0; t < T; t++)
                            for (int c = 0; c < 80; c++) out[0][c][t] = flat[idx++];
                        return out;
                    } else {
                        logger.warn("Unexpected 3D FS2 ONNX shape: {}", mel.getShape());
                        return new float[][][]{new float[80][100]};
                    }
                } else {
                    logger.warn("Unexpected FS2 ONNX output dims: {}", dims);
                    return new float[][][]{new float[80][100]};
                }
            }
        } catch (Exception e) {
            logger.warn("FastSpeech2 ONNX failed: {}", e.getMessage());
            return null;
        }
    }

    private float[] runHiFiGAN(float[][][] mel) throws Exception {
        // Use ONNX Runtime directly to guarantee rank-2 [T,80] input without implicit batching
        java.nio.file.Path modelPath = resolvePathOrClasspath(hifiganPath, ".onnx");
        java.nio.file.Path finalPath = modelPath != null ? modelPath : java.nio.file.Paths.get(hifiganPath);
        int T = mel[0][0].length;
        float[][] t80 = new float[T][80]; // [T,80]
        for (int t = 0; t < T; t++) {
            for (int c = 0; c < 80; c++) t80[t][c] = mel[0][c][t];
        }
        try (OrtEnvironment env = OrtEnvironment.getEnvironment();
             SessionOptions opts = new SessionOptions();
             OrtSession session = env.createSession(finalPath.toString(), opts)) {
            try (OnnxTensor input = OnnxTensor.createTensor(env, t80)) {
                java.util.Map<String, OnnxTensor> feeds = new java.util.HashMap<>();
                feeds.put("logmel", input); // signature input name
                try (OrtSession.Result result = session.run(feeds)) {
                    OnnxValue outVal = result.get(0);
                    if (!(outVal instanceof OnnxTensor)) {
                        throw new IllegalStateException("HiFiGAN output is not a tensor");
                    }
                    OnnxTensor outT = (OnnxTensor) outVal;
                    long[] shape = outT.getInfo().getShape(); // expect [T,1]
                    if (shape.length == 2 && shape[1] == 1) {
                        float[][] y = (float[][]) outT.getValue();
                        float[] wav = new float[(int) shape[0]];
                        for (int i = 0; i < shape[0]; i++) wav[i] = y[i][0];
                        return wav;
                    }
                    if (shape.length == 1) {
                        return (float[]) outT.getValue();
                    }
                    if (shape.length == 2 && shape[0] == 1) {
                        float[][] y = (float[][]) outT.getValue();
                        return y[0];
                    }
                    // Fallback: flatten
                    float[] buf = outT.getFloatBuffer().array();
                    return buf;
                }
            }
        }
    }



    private java.nio.file.Path resolvePathOrClasspath(String configuredPath, String expectedExt) {
        try {
            java.nio.file.Path p = java.nio.file.Paths.get(configuredPath);
            if (java.nio.file.Files.exists(p)) return p;
        } catch (Exception ignore) {
        }
        try (java.io.InputStream is = getClass().getClassLoader().getResourceAsStream(configuredPath)) {
            if (is != null) {
                java.nio.file.Path tmp = java.nio.file.Files.createTempFile("tts-model", expectedExt);
                java.nio.file.Files.copy(is, tmp, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                tmp.toFile().deleteOnExit();
                return tmp;
            }
        } catch (Exception e) {
            logger.warn("Failed to resolve classpath resource {}: {}", configuredPath, e.getMessage());
        }
        return null;
    }

    private float[] runVitsOnnx(long[] inputIds) throws Exception {
        java.nio.file.Path modelPath = resolvePathOrClasspath(vitsOnnxPath, ".onnx");
        java.nio.file.Path finalPath = modelPath != null ? modelPath : java.nio.file.Paths.get(vitsOnnxPath);

        try (OrtEnvironment env = OrtEnvironment.getEnvironment();
             SessionOptions opts = new SessionOptions();
             OrtSession session = env.createSession(finalPath.toString(), opts)) {

            // input [1, T]
            long[][] ids2d = new long[1][inputIds.length];
            System.arraycopy(inputIds, 0, ids2d[0], 0, inputIds.length);

            // input_lengths [1]
            long[] inputLengths = new long[]{inputIds.length};

            // scales [3] — noise_scale, length_scale, noise_scale_w
            float[] scales = new float[]{1.0f, 1.8f, 1.0f};

            java.util.Map<String, OnnxTensor> feeds = new java.util.HashMap<>();
            feeds.put("input", OnnxTensor.createTensor(env, ids2d));
            feeds.put("input_lengths", OnnxTensor.createTensor(env, inputLengths));
            feeds.put("scales", OnnxTensor.createTensor(env, scales));

            try (OrtSession.Result result = session.run(feeds)) {
                OnnxTensor t = (OnnxTensor) result.get(0);
                long[] shape = t.getInfo().getShape();

                if (shape.length == 1) return (float[]) t.getValue();
                if (shape.length == 2 && shape[0] == 1) return ((float[][]) t.getValue())[0];
                if (shape.length == 3 && shape[0] == 1 && shape[1] == 1) return ((float[][][]) t.getValue())[0][0];

                // fallback flatten
                return t.getFloatBuffer().array();
            }
        }
    }

    private byte[] toWavBytes(float[] samples, int sampleRate) throws Exception {
        return org.example.voicingbackend.util.AudioPlayer.toWavBytes(samples, sampleRate);
    }

}

