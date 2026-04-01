package org.example.voicingbackend.config;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import org.example.voicingbackend.repository.impl.MongoUserRepository;
import org.example.voicingbackend.repository.impl.MongoEmbeddingRepository;
import org.example.voicingbackend.service.*;
import org.example.voicingbackend.util.PythonTtsClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ServiceRegistry implements AutoCloseable {
    private static final Logger logger = LoggerFactory.getLogger(ServiceRegistry.class);

    private final MongoClient mongoClient;
    private final MongoUserRepository userRepository;
    private final MongoEmbeddingRepository embeddingRepository;
    private final AuthenticationService authService;
    private final AudioModelService audioModelService;
    private final GoogleCloudStorageService gcsService;
    private final S3StorageService s3Service;
    private final OpenAITranscriptionService openAITranscriptionService;
    private final TextToPhonemeService textToPhonemeService;
    private final TTSService ttsService;
    private final SentenceService sentenceService;
    private final PythonTtsClient pythonTtsClient;

    public ServiceRegistry() {
        ConfigurationManager config = ConfigurationManager.getInstance();
        String connString = config.getMongoDbConnectionString();
        String dbName = config.getMongoDbDatabaseName();

        logger.info("Initializing ServiceRegistry...");
        this.mongoClient = MongoClients.create(connString);
        this.userRepository = new MongoUserRepository(mongoClient, dbName);
        this.embeddingRepository = new MongoEmbeddingRepository(mongoClient, dbName);
        this.authService = new AuthenticationService(userRepository, config);
        this.audioModelService = new AudioModelService(embeddingRepository);
        this.gcsService = new GoogleCloudStorageService();
        this.s3Service = new S3StorageService();
        this.openAITranscriptionService = new OpenAITranscriptionService();
        this.textToPhonemeService = new TextToPhonemeService();
        this.pythonTtsClient = new PythonTtsClient();
        this.ttsService = new TTSService(textToPhonemeService, pythonTtsClient);
        this.sentenceService = new SentenceService();
        logger.info("ServiceRegistry initialized successfully");
    }

    // Getters
    public AuthenticationService getAuthService() { return authService; }
    public AudioModelService getAudioModelService() { return audioModelService; }
    public GoogleCloudStorageService getGcsService() { return gcsService; }
    public S3StorageService getS3Service() { return s3Service; }
    public OpenAITranscriptionService getOpenAITranscriptionService() { return openAITranscriptionService; }
    public TextToPhonemeService getTextToPhonemeService() { return textToPhonemeService; }
    public TTSService getTtsService() { return ttsService; }
    public SentenceService getSentenceService() { return sentenceService; }
    public PythonTtsClient getPythonTtsClient() { return pythonTtsClient; }
    public MongoEmbeddingRepository getEmbeddingRepository() { return embeddingRepository; }

    @Override
    public void close() {
        logger.info("Shutting down ServiceRegistry...");
        try { embeddingRepository.close(); } catch (Exception e) { logger.warn("Error closing embedding repo", e); }
        try { userRepository.close(); } catch (Exception e) { logger.warn("Error closing user repo", e); }
        try { mongoClient.close(); } catch (Exception e) { logger.warn("Error closing MongoClient", e); }
        logger.info("ServiceRegistry shutdown complete");
    }
}
