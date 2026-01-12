# VoicingBackend - Audio Model Service

A Java-based gRPC service for loading and processing PyTorch audio models for speaker embedding extraction.

## Features

- **gRPC Service**: High-performance RPC framework for model operations
- **PyTorch Model Loading**: Load PyTorch models (.pt/.pth files) for audio processing
- **Speaker Embedding**: Extract 512-dimensional speaker embeddings from audio waveforms
- **Audio Processing**: Handle raw audio samples with configurable sample rates
- **Google Cloud Storage**: Save audio files to GCS buckets with metadata
- **Audio Format Support**: WAV, MP3, FLAC, OGG, and RAW formats
- **Mock Implementation**: Includes mock PyTorch integration for testing (easily replaceable with real PyTorch)

## Project Structure

```
VoicingBackend/
├── src/main/java/org/example/
│   └── Main.java                                   # Application entry point
├── src/main/java/org/example/voicingbackend/
│   ├── controller/                                 # Presentation layer
│   │   └── AudioModelController.java               # gRPC controller
│   ├── service/                                    # Business logic layer
│   │   ├── AuthenticationService.java              # User authentication
│   │   ├── AudioModelService.java                  # Audio model operations
│   │   └── GoogleCloudStorageService.java          # GCS integration
│   ├── repository/                                 # Data access layer
│   │   ├── UserRepository.java                     # User repository interface
│   │   └── impl/
│   │       └── MongoUserRepository.java            # MongoDB implementation
│   ├── model/                                      # Domain models
│   │   ├── User.java                               # User entity
│   │   └── AudioModel.java                         # Audio model entity
│   ├── config/                                     # Configuration
│   │   └── ConfigurationManager.java               # Configuration management
│   └── util/                                       # Utilities
│       └── PyTorchAudioModelLoader.java            # PyTorch model loader (mock)
├── src/main/proto/
│   └── audio_model.proto                           # gRPC service definition
├── src/main/resources/
│   └── application.properties                      # Application configuration
└── README.md                                       # This file
```

### Architecture Overview

The project follows a **layered architecture** with clear separation of concerns:

- **Controller Layer**: Handles gRPC requests and responses
- **Service Layer**: Contains business logic and orchestration
- **Repository Layer**: Manages data access and persistence
- **Model Layer**: Defines domain entities and data structures
- **Config Layer**: Manages application configuration
- **Util Layer**: Contains utility classes and helpers

## API Overview

### Service Methods

1. **LoadModel**: Load a PyTorch model from file path
2. **ProcessAudio**: Process audio samples to extract speaker embeddings
3. **SaveAudioToGCS**: Save audio samples to Google Cloud Storage
4. **GetModelInfo**: Get information about the currently loaded model
5. **UnloadModel**: Unload the current model

### Audio Model Specifications

- **Input**: Raw audio waveform samples (normalized to [-1, 1])
- **Input Shape**: (batch, 1, time) where time is the number of audio samples
- **Sample Rate**: 16kHz (configurable)
- **Output**: 512-dimensional speaker embedding vector
- **Model Format**: PyTorch (.pt or .pth files)

## Getting Started

### Prerequisites

- Java 23 or higher
- Maven 3.6+
- MongoDB 4.4+ (local or cloud instance)
- Google Cloud Platform account
- Google Cloud Storage bucket
- Google Cloud service account with Storage permissions

### Installation

1. Clone the repository:
```bash
git clone <repository-url>
cd VoicingBackend
```

2. Compile the project:
```bash
mvn clean compile
```

3. Configure MongoDB:
   - Install MongoDB locally or use MongoDB Atlas
   - Update `src/main/resources/application.properties` with your MongoDB connection string

4. Configure Google Cloud Storage:
   - Update `src/main/resources/application.properties` with your GCP settings
   - Set up service account credentials

5. Run the server:
```bash
mvn exec:java -Dexec.mainClass="org.example.Main"
```

The gRPC server will start on port 9090.

### Usage Example

```java
// Load a PyTorch model
LoadModelRequest loadRequest = LoadModelRequest.newBuilder()
    .setModelPath("/path/to/your/model.pt")
    .setConfig(ModelConfig.newBuilder()
        .setExpectedSampleRate(16000)
        .setNormalizeAudio(true)
        .build())
    .build();

// Process audio samples
ProcessAudioRequest audioRequest = ProcessAudioRequest.newBuilder()
    .addAllAudioSamples(Arrays.asList(0.1f, 0.2f, 0.3f, ...)) // Your audio samples
    .setSampleRate(16000)
    .setTimestamp(System.currentTimeMillis())
    .build();

// Save audio to Google Cloud Storage
SaveAudioRequest saveRequest = SaveAudioRequest.newBuilder()
    .addAllAudioSamples(Arrays.asList(0.1f, 0.2f, 0.3f, ...)) // Your audio samples
    .setSampleRate(16000)
    .setBucketName("my-audio-bucket")
    .setFileName("audio_20231201_143022.wav")
    .setFormat(AudioFormat.WAV)
    .putMetadata("speaker_id", "user123")
    .putMetadata("session_id", "session456")
    .setTimestamp(System.currentTimeMillis())
    .build();
```

## MongoDB Setup

### 1. Install MongoDB

#### Option A: Local Installation
1. Download MongoDB Community Server from [mongodb.com](https://www.mongodb.com/try/download/community)
2. Install and start the MongoDB service
3. Default connection: `mongodb://localhost:27017`

#### Option B: MongoDB Atlas (Cloud)
1. Go to [MongoDB Atlas](https://www.mongodb.com/atlas)
2. Create a free cluster
3. Get your connection string from the cluster dashboard

### 2. Configure the Application
Update `src/main/resources/application.properties`:

```properties
# MongoDB Configuration
mongodb.connection.string=mongodb://localhost:27017
mongodb.database.name=voicing_backend

# JWT Configuration
jwt.secret.key=your-secret-key-should-be-at-least-256-bits-long-for-production
jwt.expiration.hours=24
```

### 3. Database Collections
The application will automatically create the following collections:
- `users` - User accounts and authentication data

## Google Cloud Storage Setup
1. Go to [Google Cloud Console](https://console.cloud.google.com/)
2. Create a new project or select an existing one
3. Enable the Cloud Storage API

### 2. Create a Storage Bucket
1. Navigate to Cloud Storage > Buckets
2. Click "Create Bucket"
3. Choose a globally unique name
4. Select your preferred location and storage class
5. Configure access control (recommended: uniform bucket-level access)

### 3. Create a Service Account
1. Go to IAM & Admin > Service Accounts
2. Click "Create Service Account"
3. Give it a name (e.g., "voicing-backend-sa")
4. Grant the "Storage Object Admin" role
5. Create and download the JSON key file

### 4. Configure the Application
Update `src/main/resources/application.properties`:

```properties
# Google Cloud Storage Configuration
gcs.project.id=your-gcp-project-id
gcs.bucket.name=your-audio-bucket-name
gcs.credentials.path=/path/to/your/service-account-key.json

# Audio Processing Configuration
audio.default.sample.rate=16000
audio.default.format=WAV
audio.max.file.size.mb=100
```

### 5. Set Up Authentication
Option 1: Service Account Key File
- Place the downloaded JSON key file in a secure location
- Update the `gcs.credentials.path` in application.properties

Option 2: Application Default Credentials (Recommended for production)
- Run `gcloud auth application-default login`
- The application will automatically use these credentials

## Configuration

### Model Configuration

- `confidence_threshold`: Minimum confidence score (default: 0.5)
- `expected_sample_rate`: Expected audio sample rate (default: 16000)
- `normalize_audio`: Whether to normalize audio to [-1, 1] range (default: true)
- `additional_params`: Additional model-specific parameters

### Server Configuration

- **Port**: 9090 (configurable in Main.java)
- **Logging**: SLF4J with Logback backend

## Development

### Adding Real PyTorch Integration

To replace the mock implementation with real PyTorch:

1. Add PyTorch Java dependencies to `pom.xml`
2. Replace `MockPyTorchModule` with actual PyTorch `Module`
3. Replace `MockTensor` with PyTorch `Tensor`
4. Update the `forward()` method to use real PyTorch inference

### Extending the Service

- Add new RPC methods to `audio_model.proto`
- Implement the methods in `AudioModelService.java`
- Add corresponding functionality to `PyTorchAudioModelLoader.java`

## Testing

The project includes a mock PyTorch implementation for testing purposes. You can:

1. Test the gRPC service without requiring actual PyTorch models
2. Verify the audio processing pipeline
3. Test the embedding extraction logic

## License

[Add your license information here]

## Contributing

[Add contribution guidelines here]
