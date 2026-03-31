package org.example.voicingbackend.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import org.example.voicingbackend.phonemes.PhonemeServiceGrpc;
import org.example.voicingbackend.phonemes.TextRequest;
import org.example.voicingbackend.phonemes.TokenResponse;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;

public class PythonTtsClient {

    public long[] fetchTokenIdsFromhttp(String text) throws Exception {

        HttpClient client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)  // <-- add this
                .build();

        String json = new ObjectMapper().writeValueAsString(
                Map.of("text", text)
        );

        System.out.println("text to endpoint: " + json);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:8000/tokenize"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response =
                client.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new RuntimeException(
                    "Tokenizer request failed: " + response.statusCode() + " " + response.body()
            );
        }

        ObjectMapper mapper = new ObjectMapper();
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

        // 1. Create channel (connect to your Docker gRPC server)
        ManagedChannel channel = ManagedChannelBuilder
                .forAddress("localhost", 50051)
                .usePlaintext()
                .build();

        // 2. Create stub (client)
        PhonemeServiceGrpc.PhonemeServiceBlockingStub stub =
                PhonemeServiceGrpc.newBlockingStub(channel);

        // 3. Build request
        TextRequest request = TextRequest.newBuilder()
                .setText(text)
                .build();

        // 4. Call gRPC method
        TokenResponse response = stub.tokenize(request);

        // 5. Convert to long[]
        long[] ids = new long[response.getIdsCount()];

        for (int i = 0; i < response.getIdsCount(); i++) {
            ids[i] = response.getIds(i);
        }

        // 6. Shutdown channel
        channel.shutdown();
        System.out.println(ids);
        return ids;
    }
}

