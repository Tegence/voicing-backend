package org.example.voicingbackend.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;

public class PythonTtsClient {

    public long[] fetchTokenIdsFromPython(String text) throws Exception {

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
}

