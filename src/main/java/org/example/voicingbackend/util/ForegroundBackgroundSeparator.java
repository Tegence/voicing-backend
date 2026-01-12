package org.example.voicingbackend.util;

import ai.djl.MalformedModelException;
import ai.djl.inference.Predictor;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDManager;
import ai.djl.repository.zoo.Criteria;
import ai.djl.repository.zoo.ZooModel;
import ai.djl.translate.TranslateException;

import java.io.IOException;
import java.nio.file.Path;

public final class ForegroundBackgroundSeparator implements AutoCloseable {
    private final ZooModel<NDList, NDList> model;
    private final Predictor<NDList, NDList> predictor;
    private final boolean isDemucs;

    public ForegroundBackgroundSeparator(Path modelPath) throws IOException, MalformedModelException, ai.djl.repository.zoo.ModelNotFoundException {
        String fileName = modelPath.getFileName().toString().toLowerCase();
        String engine = null;
        if (fileName.endsWith(".onnx")) {
            engine = "OnnxRuntime";
        } else if (fileName.endsWith(".pt") || fileName.endsWith(".pth")) {
            engine = "PyTorch";
        }
        this.isDemucs = fileName.contains("demucs");
        Criteria.Builder<NDList, NDList> b = Criteria.builder()
                .setTypes(NDList.class, NDList.class)
                .optModelPath(modelPath);
        if (engine != null) {
            b.optEngine(engine);
        }
        Criteria<NDList, NDList> criteria = b.build();
        this.model = criteria.loadModel();
        this.predictor = model.newPredictor();
    }

    public SeparationOutput separate(float[] mono, int sampleRate, boolean returnBackground) throws TranslateException {
        try (NDManager manager = NDManager.newBaseManager()) {
            if (isDemucs) {
                int tOrig = mono.length;
                int tValid = demucsValidLength(tOrig);
                float[] inputPadded;
                if (tValid != tOrig) {
                    inputPadded = new float[tValid];
                    System.arraycopy(mono, 0, inputPadded, 0, tOrig);
                } else {
                    inputPadded = mono;
                }
                NDArray x = manager.create(inputPadded).reshape(1, 1, tValid); // [1, 1, T]
                NDList out = predictor.predict(new NDList(x));
                NDArray y = out.head(); // expect [1, 1, T]
                long[] shape = y.getShape().getShape();
                if (shape.length != 3 || shape[0] != 1 || shape[1] != 1) {
                    throw new IllegalStateException("Unexpected Demucs output shape: " + y.getShape());
                }
                float[] enhancedFull = y.reshape(-1).toFloatArray();
                float[] fg = new float[tOrig];
                System.arraycopy(enhancedFull, 0, fg, 0, tOrig);
                float[] bg = null;
                if (returnBackground) {
                    float[] residual = new float[enhancedFull.length];
                    for (int i = 0; i < enhancedFull.length; i++) {
                        float inVal = i < tOrig ? mono[i] : 0f;
                        residual[i] = inVal - enhancedFull[i];
                    }
                    bg = new float[tOrig];
                    System.arraycopy(residual, 0, bg, 0, tOrig);
                }
                return new SeparationOutput(fg, bg);
            } else {
                NDArray x = manager.create(mono).reshape(1, mono.length); // [1, T]
                NDList out = predictor.predict(new NDList(x));
                NDArray y = out.head(); // expect [1, T, 2]
                long[] shape = y.getShape().getShape();
                if (shape.length != 3 || shape[2] != 2) {
                    throw new IllegalStateException("Unexpected model output shape: " + y.getShape());
                }
                float[] fg = y.get(":, :, 0").reshape(-1).toFloatArray();
                float[] bg = returnBackground ? y.get(":, :, 1").reshape(-1).toFloatArray() : null;
                return new SeparationOutput(fg, bg);
            }
        }
    }

    private static int demucsValidLength(int length) {
        int depth = 5;
        int kernel = 8;
        int stride = 4;
        int resample = 4;
        int len = (int) Math.ceil(length * (double) resample);
        for (int i = 0; i < depth; i++) {
            len = (int) (Math.ceil(((len - kernel) / (double) stride)) + 1);
            len = Math.max(len, 1);
        }
        for (int i = 0; i < depth; i++) {
            len = (len - 1) * stride + kernel;
        }
        return (int) Math.ceil(len / (double) resample);
    }

    @Override
    public void close() {
        predictor.close();
        model.close();
    }

    public static final class SeparationOutput {
        public final float[] foreground;
        public final float[] background;
        public SeparationOutput(float[] fg, float[] bg) { this.foreground = fg; this.background = bg; }
    }
}


