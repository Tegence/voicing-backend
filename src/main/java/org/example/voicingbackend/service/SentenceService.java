package org.example.voicingbackend.service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.stream.Collectors;

/**
 * Generates simple coherent sentences of a target word length and random word juxtapositions.
 * This is a lightweight, deterministic utility without external dependencies.
 */
public class SentenceService {
    private static final List<String> DEFAULT_TOPICS = Arrays.asList(
            "technology", "nature", "art", "music", "science", "history", "travel", "food", "education", "health"
    );

    private static final List<String> WORD_BANK = Arrays.asList(
            "adaptive", "balanced", "curious", "dynamic", "elegant", "fluent", "gentle", "harmonic", "insightful", "joyful",
            "kinetic", "lucid", "mindful", "nuanced", "open", "poised", "quiet", "robust", "spirited", "thoughtful",
            "uplifting", "vivid", "warm", "youthful", "zealous", "bridge", "river", "forest", "whisper", "echo",
            "canvas", "melody", "pulse", "rhythm", "pattern", "spark", "glimmer", "horizon", "harbor", "lantern",
            "pathway", "garden", "library", "compass", "journey", "harvest", "dialogue", "insight", "notion", "concept"
    );

    private final Random random;

    public SentenceService() {
        this.random = new Random();
    }

    public String generateSentence(String topic, int minWords, int maxWords) {
        int lower = Math.max(5, minWords);
        int upper = Math.max(lower, maxWords);
        int target = lower + random.nextInt(upper - lower + 1);

        String seedTopic = (topic == null || topic.isBlank())
                ? DEFAULT_TOPICS.get(random.nextInt(DEFAULT_TOPICS.size()))
                : topic.trim().toLowerCase();

        // Template-driven generation for coherence
        List<String> sentence = new ArrayList<>();
        sentence.add(capitalize("in " + seedTopic + ","));
        sentence.add("we");
        sentence.add(select("often", "regularly", "frequently", "sometimes"));
        sentence.add(select("discover", "notice", "explore", "observe"));
        sentence.add(select("how", "that"));
        sentence.add(select("small", "subtle", "unexpected", "remarkable"));
        sentence.add(select("changes", "patterns", "moments", "connections"));
        sentence.add(select("create", "shape", "influence", "transform"));
        sentence.add(select("deeper", "lasting", "meaningful", "tangible"));
        sentence.add(select("outcomes", "impressions", "experiences", "results"));
        sentence.add("as");
        sentence.add(select("people", "teams", "communities", "travellers"));
        sentence.add(select("listen", "learn", "adapt", "collaborate"));

        // Add descriptive tail until reaching target length
        while (sentence.size() < target - 2) {
            sentence.add(select("with", "through", "by"));
            sentence.add(select(
                    "careful attention", "practical insight", "creative curiosity", "shared purpose",
                    "patient reflection", "steady practice", "gentle experimentation", "open dialogue"
            ));
        }

        // Close naturally
        sentence.add(select("each day", "over time", "in practice", "in context"));
        sentence.add(select("tells a story.", "builds momentum.", "reveals clarity.", "makes a difference."));

        // Trim or pad to exact target word count
        List<String> words = ensureWordCount(sentence, target);
        return String.join(" ", words);
    }

    public List<String> generateJuxtaposition(int numWords) {
        int n = Math.max(1, numWords);
        List<String> pool = new ArrayList<>(WORD_BANK);
        Collections.shuffle(pool, random);
        return pool.subList(0, Math.min(n, pool.size()));
    }

    private String select(String... options) {
        return options[random.nextInt(options.length)];
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static List<String> ensureWordCount(List<String> tokens, int target) {
        List<String> words = tokens.stream()
                .flatMap(t -> Arrays.stream(t.split("\\s+")))
                .collect(Collectors.toCollection(ArrayList::new));
        if (words.size() > target) {
            return new ArrayList<>(words.subList(0, target));
        }
        if (words.size() < target) {
            // pad with neutral words
            while (words.size() < target) {
                words.add("and");
            }
        }
        return words;
    }
}



