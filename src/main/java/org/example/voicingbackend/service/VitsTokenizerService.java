package org.example.voicingbackend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.util.*;

public class VitsTokenizerService {
    private static final Logger logger = LoggerFactory.getLogger(VitsTokenizerService.class);

    private final Map<String, Integer> vocab = new HashMap<>();
    private final int blankId;
    private final boolean addBlank;
    private final TextToPhonemeService g2p;

    public VitsTokenizerService(String configResourcePath) {
        this.g2p = new TextToPhonemeService();

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

            logger.info("Tokenizer loaded. Vocab size: {}, Blank ID: {}, Add blank: {}",
                    vocab.size(), blankId, addBlank);

        } catch (Exception e) {
            throw new RuntimeException("Failed to load tokenizer config", e);
        }
    }

    public long[] textToIds(String text) {
        logger.debug("Original text: {}", text);

        if (text == null || text.isEmpty())
            return new long[0];

        text = normalize(text);

        TextToPhonemeService.Result res = g2p.convert(text, "en",
                TextToPhonemeService.PhonemeFormat.IPA, "en-ng");
        String phonemes = cleanPhonemes(String.join("", res.flattened));

        logger.debug("Clean phonemes: {}", phonemes);

        List<Long> ids = new ArrayList<>();

        for (int i = 0; i < phonemes.length(); ) {
            int codePoint = phonemes.codePointAt(i);
            String symbol = new String(Character.toChars(codePoint));
            Integer id = vocab.get(symbol);

            if (id != null) {
                ids.add(id.longValue());
            } else {
                if (!symbol.equals(" "))
                    logger.debug("Unknown phoneme: {}", symbol);
            }

            i += Character.charCount(codePoint);
        }

        if (addBlank)
            ids = intersperse(ids, blankId);

        long[] result = ids.stream().mapToLong(Long::longValue).toArray();
        logger.debug("Token IDs length: {}", result.length);
        return result;
    }

    private String normalize(String text) {
        text = text.toLowerCase();
        text = text.trim();
        text = text.replaceAll("\\s+", " ");
        return text;
    }

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
                .replaceAll("[ˈˌ]", "")
                .replaceAll("[|‖]", "")
                .replaceAll("[^\\p{L}\\p{M} ]", "")
                .replaceAll("\\s+", " ")
                .trim();
    }
}
