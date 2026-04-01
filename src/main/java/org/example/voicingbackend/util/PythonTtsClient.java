package org.example.voicingbackend.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import org.example.voicingbackend.config.ConfigurationManager;
import org.example.voicingbackend.phonemes.PhonemeServiceGrpc;
import org.example.voicingbackend.phonemes.TextRequest;
import org.example.voicingbackend.phonemes.TokenResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class PythonTtsClient {
    private static final Logger logger = LoggerFactory.getLogger(PythonTtsClient.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    private final HttpClient httpClient;
    private final String httpEndpoint;
    private final String grpcHost;
    private final String grpcPort;
    private final boolean grpcUseTls;

    public PythonTtsClient() {
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .build();
        ConfigurationManager cfg = ConfigurationManager.getInstance();
        this.httpEndpoint = cfg.getString("tts.tokenizer.http.endpoint", "http://localhost:8000/tokenize");
        this.grpcHost = cfg.getString("phoneme.remote.host", "localhost");
        this.grpcPort = cfg.getString("phoneme.remote.port", "443");
        this.grpcUseTls = cfg.getBoolean("phoneme.remote.tls", true);
    }

    public long[] fetchTokenIdsFromhttp(String text) throws Exception {
        String json = mapper.writeValueAsString(Map.of("text", text));
        logger.debug("Tokenizer HTTP request: {}", json);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(httpEndpoint))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response =
                httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new RuntimeException(
                    "Tokenizer request failed: " + response.statusCode() + " " + response.body()
            );
        }

        JsonNode node = mapper.readTree(response.body());
        JsonNode idsNode = node.get("ids");

        if (idsNode == null || !idsNode.isArray()) {
            throw new RuntimeException("Tokenizer response missing ids: " + response.body());
        }

        long[] ids = new long[idsNode.size()];
        for (int i = 0; i < idsNode.size(); i++) {
            ids[i] = idsNode.get(i).asLong();
        }
        return ids;
    }

    public long[] fetchTokenIdsFromGrpc(String text) {
        ManagedChannelBuilder<?> builder =
                ManagedChannelBuilder.forTarget(grpcHost + ":" + grpcPort);
        if (!grpcUseTls) {
            builder.usePlaintext();
        }
        ManagedChannel channel = builder.build();

        try {
            PhonemeServiceGrpc.PhonemeServiceBlockingStub stub =
                    PhonemeServiceGrpc.newBlockingStub(channel);

            TextRequest request = TextRequest.newBuilder()
                    .setText(text)
                    .build();

            TokenResponse response = stub.tokenize(request);

            long[] ids = new long[response.getIdsCount()];
            for (int i = 0; i < response.getIdsCount(); i++) {
                ids[i] = response.getIds(i);
            }

            logger.debug("gRPC tokenize returned {} ids", ids.length);
            return ids;
        } finally {
            channel.shutdown();
            try {
                if (!channel.awaitTermination(5, TimeUnit.SECONDS)) {
                    channel.shutdownNow();
                }
            } catch (InterruptedException e) {
                channel.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }
}
