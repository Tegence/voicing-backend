package org.example.voicingbackend.service;

import org.example.voicingbackend.config.ConfigurationManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Minimal OpenAI transcription client using multipart/form-data to call audio transcriptions.
 */
public class OpenAITranscriptionService {
    private static final Logger logger = LoggerFactory.getLogger(OpenAITranscriptionService.class);

    public static class Result {
        public final boolean success;
        public final String text;
        public final String error;
        public Result(boolean success, String text, String error) {
            this.success = success; this.text = text; this.error = error;
        }
    }

    public Result transcribe(byte[] fileBytes, String fileName, String model) {
        return transcribe(fileBytes, fileName, model, null);
    }

    public Result transcribe(byte[] fileBytes, String fileName, String model, java.util.Map<String, String> options) {
        try {
            String apiKey = ConfigurationManager.getInstance().getString("openai.api.key", "");
            if (apiKey == null || apiKey.isBlank()) {
                return new Result(false, null, "openai.api.key not configured");
            }
            if (model == null || model.isBlank()) model = "whisper-1";

            String boundary = "----WebKitFormBoundary" + UUID.randomUUID();
            String contentType = "multipart/form-data; boundary=" + boundary;

            StringBuilder sb = new StringBuilder();
            // model part
            sb.append("--").append(boundary).append("\r\n");
            sb.append("Content-Disposition: form-data; name=\"model\"\r\n\r\n");
            sb.append(model).append("\r\n");
            // If raw PCM, wrap into WAV using provided or default parameters
            if (isPcm(fileName, options)) {
                // Defaults; can be overridden by options or application.properties
                int sr = getOptionInt(options, "sample_rate",
                        ConfigurationManager.getInstance().getInt("stt.pcm.sample.rate", 16000));
                int channels = getOptionInt(options, "channels",
                        ConfigurationManager.getInstance().getInt("stt.pcm.channels", 1));
                int bitsPerSample = getOptionInt(options, "bits",
                        ConfigurationManager.getInstance().getInt("stt.pcm.bits", 16));
                fileBytes = wrapPcmToWav(fileBytes, sr, channels, bitsPerSample);
                if (fileName == null || fileName.isBlank()) fileName = "audio.wav";
                else fileName = fileName.replaceAll("\\.pcm$", ".wav");
            }

            // file part header
            sb.append("--").append(boundary).append("\r\n");
            sb.append("Content-Disposition: form-data; name=\"file\"; filename=\"")
              .append(fileName == null || fileName.isBlank() ? "audio.wav" : fileName)
              .append("\"\r\n");
            sb.append("Content-Type: application/octet-stream\r\n\r\n");
            byte[] head = sb.toString().getBytes(StandardCharsets.UTF_8);
            byte[] tail = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);

            byte[] body = new byte[head.length + fileBytes.length + tail.length];
            System.arraycopy(head, 0, body, 0, head.length);
            System.arraycopy(fileBytes, 0, body, head.length, fileBytes.length);
            System.arraycopy(tail, 0, body, head.length + fileBytes.length, tail.length);

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.openai.com/v1/audio/transcriptions"))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", contentType)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                    .build();

            HttpClient client = HttpClient.newHttpClient();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
                String text = extractTextFromJson(resp.body());
                return new Result(true, text, null);
            } else {
                logger.warn("OpenAI transcription failed: status={} body={}", resp.statusCode(), resp.body());
                return new Result(false, null, "OpenAI error: " + resp.statusCode());
            }
        } catch (Exception e) {
            logger.error("OpenAI transcription exception: {}", e.getMessage(), e);
            return new Result(false, null, e.getMessage());
        }
    }

    private boolean isPcm(String fileName, java.util.Map<String, String> options) {
        boolean byName = false;
        if (fileName != null) {
            String lower = fileName.toLowerCase();
            byName = lower.endsWith(".pcm") || lower.endsWith(".raw");
        }
        if (options == null || options.isEmpty()) return byName;
        String format = options.getOrDefault("format", "").toLowerCase();
        String isPcm = options.getOrDefault("is_pcm", "").toLowerCase();
        return byName || format.contains("pcm") || format.contains("raw") || isPcm.equals("1") || isPcm.equals("true");
    }

    private int getOptionInt(java.util.Map<String, String> options, String key, int defaultValue) {
        if (options == null) return defaultValue;
        try {
            String v = options.get(key);
            if (v == null || v.isBlank()) return defaultValue;
            return Integer.parseInt(v.trim());
        } catch (Exception ignore) { return defaultValue; }
    }

    private byte[] wrapPcmToWav(byte[] pcm, int sampleRate, int channels, int bitsPerSample) {
        int byteRate = sampleRate * channels * bitsPerSample / 8;
        int blockAlign = channels * bitsPerSample / 8;
        int subchunk2Size = pcm.length;
        int chunkSize = 36 + subchunk2Size;

        byte[] header = new byte[44];
        // RIFF header
        header[0] = 'R'; header[1] = 'I'; header[2] = 'F'; header[3] = 'F';
        writeIntLE(header, 4, chunkSize);
        header[8] = 'W'; header[9] = 'A'; header[10] = 'V'; header[11] = 'E';
        // fmt chunk
        header[12] = 'f'; header[13] = 'm'; header[14] = 't'; header[15] = ' ';
        writeIntLE(header, 16, 16); // Subchunk1Size (16 for PCM)
        writeShortLE(header, 20, (short) 1); // AudioFormat = 1 (PCM)
        writeShortLE(header, 22, (short) channels);
        writeIntLE(header, 24, sampleRate);
        writeIntLE(header, 28, byteRate);
        writeShortLE(header, 32, (short) blockAlign);
        writeShortLE(header, 34, (short) bitsPerSample);
        // data chunk
        header[36] = 'd'; header[37] = 'a'; header[38] = 't'; header[39] = 'a';
        writeIntLE(header, 40, subchunk2Size);

        byte[] wav = new byte[header.length + pcm.length];
        System.arraycopy(header, 0, wav, 0, header.length);
        System.arraycopy(pcm, 0, wav, header.length, pcm.length);
        return wav;
    }

    private void writeIntLE(byte[] buf, int offset, int value) {
        buf[offset] = (byte) (value & 0xFF);
        buf[offset + 1] = (byte) ((value >> 8) & 0xFF);
        buf[offset + 2] = (byte) ((value >> 16) & 0xFF);
        buf[offset + 3] = (byte) ((value >> 24) & 0xFF);
    }

    private void writeShortLE(byte[] buf, int offset, short value) {
        buf[offset] = (byte) (value & 0xFF);
        buf[offset + 1] = (byte) ((value >> 8) & 0xFF);
    }

    private String extractTextFromJson(String json) {
        try {
            com.fasterxml.jackson.databind.JsonNode node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(json);
            if (node.has("text")) return node.get("text").asText();
        } catch (Exception ignore) { }
        return null;
    }
}


