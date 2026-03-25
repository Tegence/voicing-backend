package org.example.voicingbackend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.core.json.JsonReadFeature;

import java.io.InputStream;
import java.util.*;

public class VitsTokenizerService {

    private final Map<String, Integer> vocab = new HashMap<>();
    private final int blankId;
    private final boolean addBlank;

    public VitsTokenizerService(String configResourcePath) {

        try {

            ObjectMapper mapper = JsonMapper.builder()
                    .enable(JsonReadFeature.ALLOW_NON_NUMERIC_NUMBERS)
                    .build();

            InputStream is =
                    getClass().getClassLoader().getResourceAsStream(configResourcePath);

            if (is == null)
                throw new RuntimeException("Config file not found: " + configResourcePath);

            JsonNode root = mapper.readTree(is);

            JsonNode chars = root.get("characters");

            if (chars == null)
                throw new RuntimeException("Missing 'characters' section in config");

            String pad = chars.get("pad").asText();
            String characters = chars.get("characters").asText("");
            String punct = chars.get("punctuations").asText("");
            String phonemes = chars.get("phonemes").asText("");

            addBlank = root.has("add_blank") && root.get("add_blank").asBoolean(true);

            List<String> vocabList = new ArrayList<>();

            // pad is always index 0 in most VITS configs
            vocabList.add(pad);

            for (char c : characters.toCharArray())
                vocabList.add(String.valueOf(c));

            for (char c : punct.toCharArray())
                vocabList.add(String.valueOf(c));

            for (char c : phonemes.toCharArray())
                vocabList.add(String.valueOf(c));

            for (int i = 0; i < vocabList.size(); i++)
                vocab.put(vocabList.get(i), i);

            if (!vocab.containsKey(pad))
                throw new RuntimeException("Pad symbol not found in vocab");

            blankId = vocab.get(pad);

            System.out.println("Tokenizer loaded. Vocab size: " + vocab.size());
            System.out.println("Blank ID: " + blankId);
            System.out.println("Add blank: " + addBlank);

        } catch (Exception e) {
            throw new RuntimeException("Failed to load tokenizer config", e);
        }
    }

    public long[] textToIds(String text) {

        System.out.println("Original text: " + text);

        if (text == null || text.isEmpty())
            return new long[0];

        // Normalize
        text = normalize(text);

        // Phonemize using centralized gRPC service
        org.example.voicingbackend.service.TextToPhonemeService g2p = new org.example.voicingbackend.service.TextToPhonemeService();
        org.example.voicingbackend.service.TextToPhonemeService.Result res = g2p.convert(text, "en", org.example.voicingbackend.service.TextToPhonemeService.PhonemeFormat.IPA, "en-ng");
        String phonemes = cleanPhonemes(String.join("", res.flattened));

        System.out.println("Clean phonemes: " + phonemes);

        List<Long> ids = new ArrayList<>();

        // Unicode-safe phoneme iteration
        for (int i = 0; i < phonemes.length(); ) {

            int codePoint = phonemes.codePointAt(i);

            String symbol = new String(Character.toChars(codePoint));

            Integer id = vocab.get(symbol);

            if (id != null) {
                ids.add(id.longValue());
            } else {
                if (!symbol.equals(" "))
                    System.out.println("Unknown phoneme: " + symbol);
            }

            i += Character.charCount(codePoint);
        }

        // Add blanks
        if (addBlank)
            ids = intersperse(ids, blankId);

        long[] result = ids.stream().mapToLong(Long::longValue).toArray();

        System.out.println("Token IDs length: " + result.length);

        return result;
    }

    private String normalize(String text) {

        text = text.toLowerCase();
        text = text.trim();

        // optional: collapse multiple spaces
        text = text.replaceAll("\\s+", " ");

        return text;
    }

    /**
     * Official VITS blank intersperse
     *
     * Example:
     * input:  [h,e,l,l,o]
     * output: [_,h,_,e,_,l,_,l,_,o,_]
     */
    private List<Long> intersperse(List<Long> original, int blankId) {

        List<Long> result = new ArrayList<>();

        result.add((long) blankId);

        for (Long id : original) {
            result.add(id);
            result.add((long) blankId);
        }

        return result;
    }

    public int getBlankId() {
        return blankId;
    }

    public int getVocabSize() {
        return vocab.size();
    }

    private String cleanPhonemes(String phonemes) {

        if (phonemes == null)
            return "";

        return phonemes
                .replaceAll("[ˈˌ]", "")   // remove stress markers
                .replaceAll("[|‖]", "")   // remove separators
                .replaceAll("[^\\p{L}\\p{M} ]", "") // keep only IPA letters
                .replaceAll("\\s+", " ")  // normalize spaces
                .trim();
    }

    // Removed local phonemize method in favor of TextToPhonemeService
}
