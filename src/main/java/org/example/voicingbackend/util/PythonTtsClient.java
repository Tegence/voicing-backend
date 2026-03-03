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

        HttpClient client = HttpClient.newHttpClient();

        String json = new ObjectMapper().writeValueAsString(
                Map.of("text", text)
        );

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:8000/tokenize"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();

        HttpResponse<String> response =
                client.send(request, HttpResponse.BodyHandlers.ofString());

        ObjectMapper mapper = new ObjectMapper();
        JsonNode node = mapper.readTree(response.body());

        JsonNode idsNode = node.get("ids");

        long[] ids = new long[idsNode.size()];
        for (int i = 0; i < idsNode.size(); i++)
            ids[i] = idsNode.get(i).asLong();

        return ids;
    }
}

