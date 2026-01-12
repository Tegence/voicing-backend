package org.example.voicingbackend.util;

import ai.djl.MalformedModelException;
import ai.djl.inference.Predictor;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.NDManager;
import ai.djl.repository.zoo.Criteria;
import ai.djl.repository.zoo.ModelNotFoundException;
import ai.djl.repository.zoo.ZooModel;
import ai.djl.translate.TranslateException;

import java.io.IOException;
import java.nio.file.Path;

public final class SpeakerEmbeddingExtractor implements AutoCloseable {
    private final ZooModel<NDList, NDList> model;
    private final Predictor<NDList, NDList> predictor;

    public SpeakerEmbeddingExtractor(Path modelPath) throws IOException, MalformedModelException, ModelNotFoundException {
        String fileName = modelPath.getFileName().toString().toLowerCase();
        String engine = fileName.endsWith(".onnx") ? "OnnxRuntime" : null;
        Criteria.Builder<NDList, NDList> b = Criteria.builder()
                .setTypes(NDList.class, NDList.class)
                .optModelPath(modelPath);
        if (engine != null) b.optEngine(engine);
        Criteria<NDList, NDList> criteria = b.build();
        this.model = criteria.loadModel();
        this.predictor = model.newPredictor();
    }

    public float[] embed(float[] mono, boolean addChannelDim) throws TranslateException {
        try (NDManager manager = NDManager.newBaseManager()) {
            NDArray x = manager.create(mono);
            NDArray in;
            if (addChannelDim) {
                in = x.reshape(1, 1, mono.length); // [1,1,T]
            } else {
                in = x.reshape(1, mono.length); // [1,T]
            }
            NDList out = predictor.predict(new NDList(in));
            NDArray y = out.head(); // expect [1, D]
            NDArray vec = y.reshape(-1);
             return vec.toFloatArray();
        }
    }

    @Override public void close() { predictor.close(); model.close(); }
}


