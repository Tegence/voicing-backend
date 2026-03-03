package org.example.voicingbackend.controller;

import com.google.protobuf.ByteString;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import org.example.voicingbackend.service.AuthenticationService;
import org.example.voicingbackend.service.AudioModelService;
import org.example.voicingbackend.service.GoogleCloudStorageService;
import org.example.voicingbackend.service.OpenAITranscriptionService;
import org.example.voicingbackend.service.S3StorageService;
import org.example.voicingbackend.service.TTSService;
import org.example.voicingbackend.service.TextToPhonemeService;
import org.example.voicingbackend.audiomodel.AudioModelServiceGrpc;
import org.example.voicingbackend.audiomodel.ExtractEmbeddingsRequest;
import org.example.voicingbackend.audiomodel.ExtractEmbeddingsResponse;
import org.example.voicingbackend.audiomodel.EmbeddingChunk;
import org.example.voicingbackend.audiomodel.RegisterUserRequest;
import org.example.voicingbackend.audiomodel.RegisterUserResponse;
import org.example.voicingbackend.audiomodel.LoginUserRequest;
import org.example.voicingbackend.audiomodel.LoginUserResponse;
import org.example.voicingbackend.audiomodel.ValidateTokenRequest;
import org.example.voicingbackend.audiomodel.ValidateTokenResponse;
import org.example.voicingbackend.audiomodel.UserInfo;
import org.example.voicingbackend.audiomodel.LoadModelRequest;
import org.example.voicingbackend.audiomodel.LoadModelResponse;
import org.example.voicingbackend.audiomodel.ProcessAudioRequest;
import org.example.voicingbackend.audiomodel.ProcessAudioResponse;
import org.example.voicingbackend.audiomodel.SaveAudioRequest;
import org.example.voicingbackend.audiomodel.SaveAudioResponse;
import org.example.voicingbackend.audiomodel.GetModelInfoRequest;
import org.example.voicingbackend.audiomodel.GetModelInfoResponse;
import org.example.voicingbackend.audiomodel.UnloadModelRequest;
import org.example.voicingbackend.audiomodel.UnloadModelResponse;
import org.example.voicingbackend.audiomodel.SuppressBackgroundRequest;
import org.example.voicingbackend.audiomodel.SuppressBackgroundResponse;
import org.example.voicingbackend.audiomodel.TranscribeAudioRequest;
import org.example.voicingbackend.audiomodel.TranscribeAudioResponse;
import org.example.voicingbackend.audiomodel.TextToPhonemeRequest;
import org.example.voicingbackend.audiomodel.TextToPhonemeResponse;
import org.example.voicingbackend.audiomodel.PhonemeSequence;
import org.example.voicingbackend.audiomodel.PhonemeFormat;
import org.example.voicingbackend.audiomodel.TextToSpeechRequest;
import org.example.voicingbackend.audiomodel.TextToSpeechResponse;
import org.example.voicingbackend.audiomodel.VerifyUserRequest;
import org.example.voicingbackend.audiomodel.VerifyUserResponse;
import org.example.voicingbackend.audiomodel.EnrolUserVoiceRequest;
import org.example.voicingbackend.audiomodel.EnrolUserVoiceResponse;
import org.example.voicingbackend.util.PythonTtsClient;
import org.example.voicingbackend.audiomodel.TextToSpeechvitsRequest;
import org.example.voicingbackend.audiomodel.TextToSpeechvitsResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import org.example.voicingbackend.util.AudioPlayer;
/**
 * gRPC controller for handling audio model and authentication requests
 */
public class AudioModelController extends AudioModelServiceGrpc.AudioModelServiceImplBase {
    private static final Logger logger = LoggerFactory.getLogger(AudioModelController.class);
    
    private final AuthenticationService authService;
    private final AudioModelService audioModelService;
    private final GoogleCloudStorageService gcsService;
    private final S3StorageService s3Service;
    private final OpenAITranscriptionService openAITranscriptionService;
    private final TextToPhonemeService textToPhonemeService;
    private final TTSService ttsService;
    private final org.example.voicingbackend.service.SentenceService sentenceService;
    private final org.example.voicingbackend.repository.impl.MongoEmbeddingRepository embeddingRepository;
    private final PythonTtsClient pythonTtsClient;
    
    public AudioModelController() {
        this.authService = new AuthenticationService();
        this.audioModelService = new AudioModelService();
        this.gcsService = new GoogleCloudStorageService();
        this.s3Service = new S3StorageService();
        this.embeddingRepository = new org.example.voicingbackend.repository.impl.MongoEmbeddingRepository();
        this.openAITranscriptionService = new OpenAITranscriptionService();
        this.textToPhonemeService = new TextToPhonemeService();
        this.ttsService = new TTSService();
        this.sentenceService = new org.example.voicingbackend.service.SentenceService();
        this.pythonTtsClient = new PythonTtsClient();
    }
    
    // Authentication endpoints
    
    @Override
    public void registerUser(RegisterUserRequest request, StreamObserver<RegisterUserResponse> responseObserver) {
        logger.info("Received user registration request for: {}", request.getUsername());
        
        RegisterUserResponse.Builder responseBuilder = RegisterUserResponse.newBuilder();
        
        try {
            AuthenticationService.RegistrationResult result = authService.registerUser(
                request.getUsername(),
                request.getEmail(),
                request.getPassword(),
                request.getFullName()
            );
            
            responseBuilder.setSuccess(result.isSuccess())
                          .setMessage(result.getMessage());
            
            if (result.isSuccess()) {
                responseBuilder.setUserId(result.getUserId())
                              .setToken(result.getToken());
            }
            
        } catch (Exception e) {
            logger.error("User registration failed", e);
            responseBuilder.setSuccess(false)
                          .setMessage("Registration failed: " + e.getMessage());
        }
        
        responseObserver.onNext(responseBuilder.build());
        responseObserver.onCompleted();
    }
    
    @Override
    public void loginUser(LoginUserRequest request, StreamObserver<LoginUserResponse> responseObserver) {
        logger.info("Received user login request for: {}", request.getUsername());
        
        LoginUserResponse.Builder responseBuilder = LoginUserResponse.newBuilder();
        
        try {
            AuthenticationService.LoginResult result = authService.loginUser(
                request.getUsername(),
                request.getPassword()
            );
            
            responseBuilder.setSuccess(result.isSuccess())
                          .setMessage(result.getMessage());
            
            if (result.isSuccess()) {
                responseBuilder.setUserId(result.getUserId())
                              .setToken(result.getToken());
                
                // Convert UserInfo to protobuf UserInfo
                AuthenticationService.UserInfo userInfo = result.getUserInfo();
                UserInfo.Builder userInfoBuilder = UserInfo.newBuilder()
                    .setUserId(userInfo.getUserId())
                    .setUsername(userInfo.getUsername())
                    .setEmail(userInfo.getEmail())
                    .setFullName(userInfo.getFullName())
                    .setCreatedAt(userInfo.getCreatedAt())
                    .setLastLogin(userInfo.getLastLogin());
                
                responseBuilder.setUserInfo(userInfoBuilder.build());
            }
            
        } catch (Exception e) {
            logger.error("User login failed", e);
            responseBuilder.setSuccess(false)
                          .setMessage("Login failed: " + e.getMessage());
        }
        
        responseObserver.onNext(responseBuilder.build());
        responseObserver.onCompleted();
    }
    
    @Override
    public void validateToken(ValidateTokenRequest request, StreamObserver<ValidateTokenResponse> responseObserver) {
        logger.info("Received token validation request");
        
        ValidateTokenResponse.Builder responseBuilder = ValidateTokenResponse.newBuilder();
        
        try {
            AuthenticationService.TokenValidationResult result = authService.validateToken(request.getToken());
            
            responseBuilder.setValid(result.isValid())
                          .setMessage(result.getMessage());
            
            if (result.isValid()) {
                responseBuilder.setUserId(result.getUserId())
                              .setUsername(result.getUsername());
            }
            
        } catch (Exception e) {
            logger.error("Token validation failed", e);
            responseBuilder.setValid(false)
                          .setMessage("Token validation failed: " + e.getMessage());
        }
        
        responseObserver.onNext(responseBuilder.build());
        responseObserver.onCompleted();
    }
    
    // Audio model endpoints
    
    @Override
    public void loadModel(LoadModelRequest request, StreamObserver<LoadModelResponse> responseObserver) {
        logger.info("Received load model request for: {}", request.getModelPath());
        
        LoadModelResponse.Builder responseBuilder = LoadModelResponse.newBuilder();
        
        try {
            AudioModelService.LoadModelResult result = audioModelService.loadModel(
                request.getModelPath(),
                request.getConfig()
            );
            
            responseBuilder.setSuccess(result.isSuccess())
                          .setMessage(result.getMessage());
            
            if (result.isSuccess() && result.getModelInfo() != null) {
                responseBuilder.setModelInfo(result.getModelInfo());
            }
            
        } catch (Exception e) {
            logger.error("Load model failed", e);
            responseBuilder.setSuccess(false)
                          .setMessage("Load model failed: " + e.getMessage());
        }
        
        responseObserver.onNext(responseBuilder.build());
        responseObserver.onCompleted();
    }
    
    @Override
    public void processAudio(ProcessAudioRequest request, StreamObserver<ProcessAudioResponse> responseObserver) {
        logger.info("Received process audio request with {} samples", request.getAudioSamplesCount());
        
        ProcessAudioResponse.Builder responseBuilder = ProcessAudioResponse.newBuilder();
        
        try {
            // Convert protobuf list to array
            float[] audioSamples = new float[request.getAudioSamplesCount()];
            for (int i = 0; i < request.getAudioSamplesCount(); i++) {
                audioSamples[i] = request.getAudioSamples(i);
            }
            
            AudioModelService.ProcessAudioResult result = audioModelService.processAudio(
                audioSamples,
                request.getSampleRate(),
                request.getTimestamp()
            );
            
            responseBuilder.setSuccess(result.isSuccess())
                          .setConfidence(result.getConfidence())
                          .setErrorMessage(result.getErrorMessage());
            
            if (result.isSuccess() && result.getEmbedding() != null) {
                for (float value : result.getEmbedding()) {
                    responseBuilder.addEmbedding(value);
                }
            }
            
        } catch (Exception e) {
            logger.error("Process audio failed", e);
            responseBuilder.setSuccess(false)
                          .setErrorMessage("Process audio failed: " + e.getMessage());
        }
        
        responseObserver.onNext(responseBuilder.build());
        responseObserver.onCompleted();
    }
    
    @Override
    public void saveAudioToGCS(SaveAudioRequest request, StreamObserver<SaveAudioResponse> responseObserver) {
        logger.info("Received save audio to GCS request for: {}", request.getFileName());
        
        SaveAudioResponse.Builder responseBuilder = SaveAudioResponse.newBuilder();
        
        try {
            // Convert protobuf list to array
            float[] audioSamples = new float[request.getAudioSamplesCount()];
            for (int i = 0; i < request.getAudioSamplesCount(); i++) {
                audioSamples[i] = request.getAudioSamples(i);
            }
            
            // Prefer S3 if configured
            String username = request.getMetadataMap().getOrDefault("username", "");
            org.example.voicingbackend.service.S3StorageService.SaveResult s3 = s3Service.save(
                audioSamples, request.getSampleRate(), username, request.getFileName(), request.getFormat(), request.getMetadataMap(), request.getTimestamp());
            GoogleCloudStorageService.SaveAudioResult result;
            if (s3.success) {
                result = new GoogleCloudStorageService.SaveAudioResult(true, s3.s3Uri, s3.key, s3.size, null);
            } else {
                // fallback to GCS
                result = gcsService.saveAudioToGCS(
                    audioSamples,
                    request.getSampleRate(),
                    request.getBucketName(),
                    request.getFileName(),
                    request.getFormat(),
                    request.getMetadataMap(),
                    request.getTimestamp()
                );
                if (!result.isSuccess() && s3.error != null) {
                    // prefer S3 error in response if GCS also failed
                    result = new GoogleCloudStorageService.SaveAudioResult(false, result.getGcsUri(), result.getFileName(), result.getFileSizeBytes(), s3.error);
                }
            }
            
            if (!result.isSuccess()) {
                logger.warn("GCS save failed for {}: {}", request.getFileName(), result.getErrorMessage());
            }

            responseBuilder.setSuccess(result.isSuccess())
                          .setFileSizeBytes(result.getFileSizeBytes());
            if (result.getGcsUri() != null) {
                responseBuilder.setGcsUri(result.getGcsUri());
            }
            if (result.getFileName() != null) {
                responseBuilder.setFileName(result.getFileName());
            }
            if (result.getErrorMessage() != null) {
                responseBuilder.setErrorMessage(result.getErrorMessage());
                logger.warn("GCS response error: {}", result.getErrorMessage());
            }
            
        } catch (Exception e) {
            logger.error("Save audio to GCS failed", e);
            responseBuilder.setSuccess(false)
                          .setErrorMessage("Save audio to GCS failed: " + e.getMessage());
        }
        
        responseObserver.onNext(responseBuilder.build());
        responseObserver.onCompleted();
    }
    
    @Override
    public void suppressBackground(SuppressBackgroundRequest request, StreamObserver<SuppressBackgroundResponse> responseObserver) {
        logger.info("Received suppress background request with {} samples", request.getAudioSamplesCount());
        SuppressBackgroundResponse.Builder responseBuilder = SuppressBackgroundResponse.newBuilder();
        try {
            float[] audio = new float[request.getAudioSamplesCount()];
            for (int i = 0; i < audio.length; i++) audio[i] = request.getAudioSamples(i);
            AudioModelService.SuppressBackgroundResult result = audioModelService.suppressBackground(
                    audio, request.getSampleRate(), request.getReturnBackground());
            responseBuilder.setSuccess(result.isSuccess());
            if (result.getForeground() != null) {
                for (float v : result.getForeground()) responseBuilder.addForegroundSamples(v);
            }
            if (request.getReturnBackground() && result.getBackground() != null) {
                for (float v : result.getBackground()) responseBuilder.addBackgroundSamples(v);
            }
            if (result.getErrorMessage() != null) responseBuilder.setErrorMessage(result.getErrorMessage());
        } catch (Exception e) {
            logger.error("Suppress background failed", e);
            responseBuilder.setSuccess(false).setErrorMessage("Suppress background failed: " + e.getMessage());
        }
        responseObserver.onNext(responseBuilder.build());
        responseObserver.onCompleted();
    }

    @Override
    public void extractEmbeddings(ExtractEmbeddingsRequest request, StreamObserver<ExtractEmbeddingsResponse> responseObserver) {
        logger.info("Received extract embeddings request with {} samples", request.getAudioSamplesCount());
        ExtractEmbeddingsResponse.Builder resp = ExtractEmbeddingsResponse.newBuilder();
        try {
            float[] audio = new float[request.getAudioSamplesCount()];
            for (int i = 0; i < audio.length; i++) audio[i] = request.getAudioSamples(i);
            AudioModelService.ExtractEmbeddingsResult result = audioModelService.extractEmbeddings(audio, request.getSampleRate());
            resp.setSuccess(result.success);
            if (!result.success && result.error != null) resp.setErrorMessage(result.error);
            if (result.windows != null) {
                for (AudioModelService.EmbeddingWindow w : result.windows) {
                    EmbeddingChunk.Builder b = EmbeddingChunk.newBuilder().setStartMs(w.startMs).setEndMs(w.endMs);
                    for (float v : w.vector) b.addVector(v);
                    resp.addChunks(b);
                    // persist
                    String userId = request.getUserId();
                    String audioId = request.getAudioId();
                    if (userId != null && !userId.isBlank()) {
                        embeddingRepository.save(userId, audioId, w.startMs, w.endMs, "speaker_embedding.onnx", w.vector);
                    }
                }
            }
        } catch (Exception e) {
            logger.error("Extract embeddings failed", e);
            resp.setSuccess(false).setErrorMessage("Extract embeddings failed: " + e.getMessage());
        }
        responseObserver.onNext(resp.build());
        responseObserver.onCompleted();
    }

    @Override
    public void verifyUser(VerifyUserRequest request, StreamObserver<VerifyUserResponse> responseObserver) {
        logger.info("Received verify user request for userId={} samples={} rate={}", request.getUserId(), request.getAudioSamplesCount(), request.getSampleRate());
        VerifyUserResponse.Builder resp = VerifyUserResponse.newBuilder();
        try {
            float[] audio = new float[request.getAudioSamplesCount()];
            for (int i = 0; i < audio.length; i++) audio[i] = request.getAudioSamples(i);
            AudioModelService.VerifyResult vr = audioModelService.verifyUser(request.getUserId(), audio, request.getSampleRate());
            resp.setSuccess(vr.success)
                .setScore(vr.score)
                .setPercentage(vr.percentage)
                .setVerified(vr.verified);
            if (!vr.success && vr.error != null) resp.setErrorMessage(vr.error);
        } catch (Exception e) {
            logger.error("VerifyUser failed", e);
            resp.setSuccess(false).setErrorMessage("VerifyUser failed: " + e.getMessage());
        }
        responseObserver.onNext(resp.build());
        responseObserver.onCompleted();
    }

    @Override
    public void enrolUserVoice(EnrolUserVoiceRequest request, StreamObserver<EnrolUserVoiceResponse> responseObserver) {
        logger.info("Received enrol user voice for userId={} samples={} rate={}", request.getUserId(), request.getAudioSamplesCount(), request.getSampleRate());
        EnrolUserVoiceResponse.Builder resp = EnrolUserVoiceResponse.newBuilder();
        try {
            String userId = request.getUserId();
            if (userId == null || userId.isBlank()) {
                resp.setSuccess(false).setErrorMessage("user_id is required");
                responseObserver.onNext(resp.build());
                responseObserver.onCompleted();
                return;
            }
            // Prepare audio buffer
            float[] audio = new float[request.getAudioSamplesCount()];
            for (int i = 0; i < audio.length; i++) audio[i] = request.getAudioSamples(i);

            // Save to S3 under user folder
            String fileName = (request.getAudioId() != null && !request.getAudioId().isBlank())
                    ? request.getAudioId() : (java.util.UUID.randomUUID() + ".wav");
            var s3res = s3Service.save(audio, request.getSampleRate(), userId, fileName,
                    request.getStoreFormat(), java.util.Collections.emptyMap(), System.currentTimeMillis());
            if (!s3res.success) {
                resp.setSuccess(false).setErrorMessage("S3 save failed: " + s3res.error);
                responseObserver.onNext(resp.build());
                responseObserver.onCompleted();
                return;
            }

            // Compute a single utterance-level embedding (speech-only)
            AudioModelService.SingleEmbeddingResult single = audioModelService.computeSingleEmbedding(audio, request.getSampleRate());
            if (!single.success) {
                resp.setSuccess(false).setS3Uri(s3res.s3Uri).setErrorMessage("Single embedding failed: " + single.error);
                responseObserver.onNext(resp.build());
                responseObserver.onCompleted();
                return;
            }
            // Store one embedding record covering the full speech duration
            long now = System.currentTimeMillis();
            long startMs = 0;
            long endMs = single.durationMs;
            embeddingRepository.save(userId, fileName, startMs, endMs, "speaker_embedding.onnx", single.embedding);
            resp.setSuccess(true).setS3Uri(s3res.s3Uri).setChunksStored(1);
        } catch (Exception e) {
            logger.error("enrolUserVoice failed", e);
            resp.setSuccess(false).setErrorMessage("enrolUserVoice failed: " + e.getMessage());
        }
        responseObserver.onNext(resp.build());
        responseObserver.onCompleted();
    }
    
    @Override
    public void getModelInfo(GetModelInfoRequest request, StreamObserver<GetModelInfoResponse> responseObserver) {
        logger.info("Received get model info request");
        
        GetModelInfoResponse.Builder responseBuilder = GetModelInfoResponse.newBuilder();
        
        try {
            AudioModelService.GetModelInfoResult result = audioModelService.getModelInfo();
            
            responseBuilder.setModelLoaded(result.isModelLoaded());
            
            if (result.getModelInfo() != null) {
                responseBuilder.setModelInfo(result.getModelInfo());
            }
            
        } catch (Exception e) {
            logger.error("Get model info failed", e);
            responseBuilder.setModelLoaded(false);
        }
        
        responseObserver.onNext(responseBuilder.build());
        responseObserver.onCompleted();
    }
    
    @Override
    public void unloadModel(UnloadModelRequest request, StreamObserver<UnloadModelResponse> responseObserver) {
        logger.info("Received unload model request");
        
        UnloadModelResponse.Builder responseBuilder = UnloadModelResponse.newBuilder();
        
        try {
            AudioModelService.UnloadModelResult result = audioModelService.unloadModel();
            
            responseBuilder.setSuccess(result.isSuccess())
                          .setMessage(result.getMessage());
            
        } catch (Exception e) {
            logger.error("Unload model failed", e);
            responseBuilder.setSuccess(false)
                          .setMessage("Unload model failed: " + e.getMessage());
        }
        
        responseObserver.onNext(responseBuilder.build());
        responseObserver.onCompleted();
    }

    @Override
    public void transcribeAudio(TranscribeAudioRequest request, StreamObserver<TranscribeAudioResponse> responseObserver) {
        logger.info("Received transcribe audio request: {} bytes, name={} model={}", request.getFileContent().size(), request.getFileName(), request.getModel());
        TranscribeAudioResponse.Builder resp = TranscribeAudioResponse.newBuilder();
        try {
            byte[] bytes = request.getFileContent().toByteArray();
            String fileName = request.getFileName();
            String model = request.getModel();
            java.util.Map<String, String> options = request.getOptionsMap();
            var result = openAITranscriptionService.transcribe(bytes, fileName, model, options);
            if (!result.success) {
                resp.setSuccess(false).setErrorMessage(result.error);
            } else {
                resp.setSuccess(true).setText(result.text != null ? result.text : "");
            }
        } catch (Exception e) {
            logger.error("transcribeAudio failed", e);
            resp.setSuccess(false).setErrorMessage("transcribeAudio failed: " + e.getMessage());
        }
        responseObserver.onNext(resp.build());
        responseObserver.onCompleted();
    }

    @Override
    public void textToPhoneme(TextToPhonemeRequest request, StreamObserver<TextToPhonemeResponse> responseObserver) {
        logger.info("Received textToPhoneme request: '{}' lang={} fmt={} dialect={}", request.getText(), request.getLanguage(), request.getFormat(), request.getDialect());
        TextToPhonemeResponse.Builder resp = TextToPhonemeResponse.newBuilder();
        try {
            TextToPhonemeService.PhonemeFormat fmt = request.getFormat() == PhonemeFormat.IPA
                    ? TextToPhonemeService.PhonemeFormat.IPA
                    : TextToPhonemeService.PhonemeFormat.ARPABET;
            var result = textToPhonemeService.convert(request.getText(), request.getLanguage(), fmt, request.getDialect());
            if (!result.success) {
                resp.setSuccess(false).setErrorMessage(result.error);
            } else {
                resp.setSuccess(true);
                if (result.sequences != null) {
                    for (TextToPhonemeService.Sequence s : result.sequences) {
                        PhonemeSequence.Builder b = PhonemeSequence.newBuilder().setToken(s.token);
                        for (String ph : s.phonemes) b.addPhonemes(ph);
                        resp.addSequences(b.build());
                    }
                }
                if (result.flattened != null) {
                    for (String ph : result.flattened) resp.addPhonemes(ph);
                }
                if (result.flattenedIds != null) {
                    for (Integer id : result.flattenedIds) resp.addPhonemeIds(id);
                }
            }
        } catch (Exception e) {
            logger.error("textToPhoneme failed", e);
            resp.setSuccess(false).setErrorMessage("textToPhoneme failed: " + e.getMessage());
        }
        responseObserver.onNext(resp.build());
        responseObserver.onCompleted();
    }

    @Override
    public void textToSpeech(TextToSpeechRequest request, StreamObserver<TextToSpeechResponse> responseObserver) {
        logger.info("Received textToSpeech request: '{}' sr={}", request.getText(), request.getSampleRate());
        TextToSpeechResponse.Builder resp = TextToSpeechResponse.newBuilder();
        try {
            var r = ttsService.synthesize(request.getText(), request.getSampleRate());
            if (!r.success) {
                resp.setSuccess(false).setErrorMessage(r.error);
            } else {
                resp.setSuccess(true).setSampleRate(r.sampleRate);
                for (float s : r.audio) resp.addSamples(s);
            }
        } catch (Exception e) {
            logger.error("textToSpeech failed", e);
            resp.setSuccess(false).setErrorMessage("textToSpeech failed: " + e.getMessage());
        }
        responseObserver.onNext(resp.build());
        responseObserver.onCompleted();
    }

    @Override
    public void generateSentence(org.example.voicingbackend.audiomodel.GenerateSentenceRequest request,
                                 StreamObserver<org.example.voicingbackend.audiomodel.GenerateSentenceResponse> responseObserver) {
        logger.info("Received GenerateSentence request: topic='{}' range=[{},{}]", request.getTopic(), request.getMinWords(), request.getMaxWords());
        org.example.voicingbackend.audiomodel.GenerateSentenceResponse.Builder resp = org.example.voicingbackend.audiomodel.GenerateSentenceResponse.newBuilder();
        try {
            int minW = request.getMinWords() > 0 ? request.getMinWords() : 20;
            int maxW = request.getMaxWords() > 0 ? request.getMaxWords() : 25;
            if (maxW < minW) maxW = minW;
            String sentence = sentenceService.generateSentence(request.getTopic(), minW, maxW);
            int count = sentence.isBlank() ? 0 : sentence.trim().split("\\s+").length;
            resp.setSuccess(true).setSentence(sentence).setWordCount(count);
        } catch (Exception e) {
            logger.error("generateSentence failed", e);
            resp.setSuccess(false).setErrorMessage("generateSentence failed: " + e.getMessage());
        }
        responseObserver.onNext(resp.build());
        responseObserver.onCompleted();
    }

    @Override
    public void generateJuxtaposition(org.example.voicingbackend.audiomodel.GenerateJuxtapositionRequest request,
                                      StreamObserver<org.example.voicingbackend.audiomodel.GenerateJuxtapositionResponse> responseObserver) {
        logger.info("Received GenerateJuxtaposition request: num_words={}", request.getNumWords());
        org.example.voicingbackend.audiomodel.GenerateJuxtapositionResponse.Builder resp = org.example.voicingbackend.audiomodel.GenerateJuxtapositionResponse.newBuilder();
        try {
            int n = request.getNumWords() > 0 ? request.getNumWords() : 7;
            java.util.List<String> words = sentenceService.generateJuxtaposition(n);
            resp.setSuccess(true);
            for (String w : words) resp.addWords(w);
        } catch (Exception e) {
            logger.error("generateJuxtaposition failed", e);
            resp.setSuccess(false).setErrorMessage("generateJuxtaposition failed: " + e.getMessage());
        }
        responseObserver.onNext(resp.build());
        responseObserver.onCompleted();
    }

    @Override
    public void textToSpeechVits(TextToSpeechvitsRequest request,
                                 StreamObserver<TextToSpeechvitsResponse> responseObserver) {

        TextToSpeechvitsResponse.Builder resp = TextToSpeechvitsResponse.newBuilder();

        try {
            TTSService.Result result = ttsService.synthesize(request.getText(), 22050);

            if (!result.success) {
                resp.setSuccess(false)
                        .setErrorMessage(result.error);
            } else {
                AudioPlayer.saveWav(
                        result.audio,
                        result.sampleRate,
                        "test.wav"
                );
                resp.setSuccess(true)
                        .setSampleRate(result.sampleRate);

                for (float s : result.audio)
                    resp.addSamples(s);
            }

            responseObserver.onNext(resp.build());
            responseObserver.onCompleted();

        } catch (Exception e) {
            responseObserver.onError(
                    Status.INTERNAL.withDescription(e.getMessage()).asRuntimeException()
            );
        }
    }
}
