package org.example.voicingbackend.service;

import org.example.voicingbackend.config.ConfigurationManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.text.Normalizer;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Rule-based, extensible text-to-phoneme service supporting ARPABET (primary) and basic IPA.
 * Practical baseline with dictionary + fallback rules, punctuation-preserving tokenization,
 * configurable pauses via 'sp' tokens, and mapping to phoneme IDs compatible with FS2 ONNX.
 */
public class TextToPhonemeService {
    private static final Logger logger = LoggerFactory.getLogger(TextToPhonemeService.class);

    public enum PhonemeFormat { ARPABET, IPA }

    public static class Result {
        public final boolean success;
        public final List<Sequence> sequences;
        public final List<String> flattened;
        public final List<Integer> flattenedIds;
        public final String error;
        public Result(boolean success, List<Sequence> sequences, List<String> flattened, List<Integer> flattenedIds, String error) {
            this.success = success; this.sequences = sequences; this.flattened = flattened; this.flattenedIds = flattenedIds; this.error = error;
        }
    }

    public static class Sequence {
        public final String token;        // original token (word or punctuation)
        public final List<String> phonemes; // expanded phonemes (ARPABET or IPA)
        public Sequence(String token, List<String> phonemes) { this.token = token; this.phonemes = phonemes; }
    }

    private final Map<String, List<String>> cmuDict;  // word -> ARPABET with stress (e.g., AH0, OW1)
    private final Map<String, List<String>> ipaMap;   // ARPABET -> IPA sequence
    private final Map<String, Result> cache;

    // Configurable pauses after punctuation, emit 'sp' tokens (must exist in phone map)
    private final int commaSp;
    private final int periodSp;

    public TextToPhonemeService() {
        this.cmuDict = new HashMap<>();
        if (!loadCmuFromConfig()) {
            this.cmuDict.putAll(loadSmallCmuSubset());
        }
        this.ipaMap = buildArpabetToIpaMap();
        this.cache = new ConcurrentHashMap<>();

        ConfigurationManager cfg = ConfigurationManager.getInstance();
        this.commaSp = cfg.getInt("g2p.pause.comma.sp", 1);
        this.periodSp = cfg.getInt("g2p.pause.period.sp", 3);

        // Load vocab/phone-id map once on startup so first call is fast
        ensureVocabLoaded();
    }

    public Result convert(String text, String language, PhonemeFormat fmt) {
        return convert(text, language, fmt, null);
    }

    public Result convert(String text, String language, PhonemeFormat fmt, String dialect) {
        try {
            if (text == null || text.isBlank()) {
                return new Result(false, Collections.emptyList(), Collections.emptyList(), Collections.emptyList(), "text required");
            }
            String lang = (language == null || language.isBlank())
                    ? ConfigurationManager.getInstance().getString("g2p.default.language", "en")
                    : language.toLowerCase(Locale.ROOT);
            if (!Objects.equals(lang, "en")) {
                logger.warn("Unsupported language '{}', falling back to en", lang);
            }

            // Normalize dialect - default to Nigerian English (en-ng) if not specified
            String normalizedDialect = (dialect == null || dialect.isBlank()) ? "en-ng" : dialect.toLowerCase(Locale.ROOT);

            // Cache key uses normalized input & format & language & dialect
            String cacheKey = (text + "|" + lang + "|" + fmt.name() + "|" + normalizedDialect);
            Result cached = cache.get(cacheKey);
            if (cached != null) return cached;

            // If remote phoneme service is enabled, bypass local G2P and fetch phonemes
            if (useRemotePhonemeService()) {
                Result remote = fetchFromRemote(text, lang, fmt, normalizedDialect);
                if (remote != null) {
                    cache.put(cacheKey, remote);
                    return remote;
                }
                return new Result(false, Collections.emptyList(), Collections.emptyList(), Collections.emptyList(), "remote phoneme service returned empty");
            }

            List<String> tokens = tokenizePreservePunct(text);
            List<Sequence> sequences = new ArrayList<>();
            List<String> flattened = new ArrayList<>();
            List<Integer> flattenedIds = new ArrayList<>();

            for (int i = 0; i < tokens.size(); i++) {
                String tok = tokens.get(i);

                // Punctuation handling: keep commas/sentence enders, then add 'sp' pauses
                if (isComma(tok)) {
                    sequences.add(new Sequence(tok, Collections.singletonList(tok)));
                    flattened.add(tok);
                    flattenedIds.add(phonemeToId(tok)); // ',' should exist in phone map
                    for (int k = 0; k < Math.max(0, commaSp); k++) {
                        sequences.add(new Sequence("sp", Collections.singletonList("sp")));
                        flattened.add("sp");
                        flattenedIds.add(phonemeToId("sp"));
                    }
                    continue;
                } else if (isSentenceEnd(tok)) {
                    sequences.add(new Sequence(tok, Collections.singletonList(tok)));
                    flattened.add(tok);
                    flattenedIds.add(phonemeToId(tok)); // '.', '!' or '?' should exist
                    for (int k = 0; k < Math.max(0, periodSp); k++) {
                        sequences.add(new Sequence("sp", Collections.singletonList("sp")));
                        flattened.add("sp");
                        flattenedIds.add(phonemeToId("sp"));
                    }
                    continue;
                }

                // Number expansion for pure digits: "123" -> ["one","two","three"]
                if (tok.matches("[0-9]+")) {
                    for (String word : expandDigits(tok)) {
                        List<String> arp = lookupOrGraphemeFallback(word);
                        List<String> out = (fmt == PhonemeFormat.IPA) ? toIpa(arp) : arp;
                        sequences.add(new Sequence(word, out));
                        flattened.addAll(out);
                        for (String ph : out) flattenedIds.add(phonemeToId(ph));
                    }
                } else {
                    // Lookup word in CMU dict (with stress) or fallback
                    List<String> arp = lookupOrGraphemeFallback(tok);
                    List<String> out = (fmt == PhonemeFormat.IPA) ? toIpa(arp) : arp;
                    sequences.add(new Sequence(tok, out));
                    flattened.addAll(out);
                    for (String ph : out) flattenedIds.add(phonemeToId(ph));
                }
            }

            Result res = new Result(true, sequences, flattened, flattenedIds, null);
            cache.put(cacheKey, res);
            return res;
        } catch (Exception e) {
            logger.error("g2p failed: {}", e.getMessage(), e);
            return new Result(false, Collections.emptyList(), Collections.emptyList(), Collections.emptyList(), e.getMessage());
        }
    }

    private boolean useRemotePhonemeService() {
        try {
            return ConfigurationManager.getInstance().getInt("phoneme.remote.enabled", 0) == 1;
        } catch (Exception ignore) { return false; }
    }

    private Result fetchFromRemote(String text, String lang, PhonemeFormat fmt, String dialect) {
        String host = ConfigurationManager.getInstance().getString("phoneme.remote.host", "localhost");
        int port = ConfigurationManager.getInstance().getInt("phoneme.remote.port", 50051);
        String ipaLang = ConfigurationManager.getInstance().getString("phoneme.remote.ipa.lang", "en-us");
        io.grpc.ManagedChannel channel = null;
        try {
            channel = io.grpc.ManagedChannelBuilder.forAddress(host, port).usePlaintext().build();
            // Reflective access to generated gRPC/protobuf classes to avoid compile-time dependency on generated-sources
            Class<?> svcGrpc = Class.forName("org.example.voicingbackend.phonemes.PhonemeServiceGrpc");
            java.lang.reflect.Method newBlockingStub = svcGrpc.getMethod("newBlockingStub", io.grpc.Channel.class);
            Object stub = newBlockingStub.invoke(null, channel);

            Class<?> outCfgClass = Class.forName("org.example.voicingbackend.phonemes.OutputConfig");
            Object outBld = outCfgClass.getMethod("newBuilder").invoke(null);
            if (fmt == PhonemeFormat.IPA) {
                outBld.getClass().getMethod("setEmitIpa", boolean.class).invoke(outBld, true);
                outBld.getClass().getMethod("setIpaLang", String.class).invoke(outBld, ipaLang);
            }
            // Set dialect for Nigerian English or other dialect transformations
            if (dialect != null && !dialect.isEmpty()) {
                outBld.getClass().getMethod("setDialect", String.class).invoke(outBld, dialect);
            }
            Object outCfg = outBld.getClass().getMethod("build").invoke(outBld);

            Class<?> reqClass = Class.forName("org.example.voicingbackend.phonemes.PhonemeRequest");
            Object reqBld = reqClass.getMethod("newBuilder").invoke(null);
            reqBld.getClass().getMethod("setText", String.class).invoke(reqBld, text);
            reqBld.getClass().getMethod("setOutput", outCfgClass).invoke(reqBld, outCfg);
            Object req = reqBld.getClass().getMethod("build").invoke(reqBld);

            java.lang.reflect.Method call = null;
            for (java.lang.reflect.Method m : stub.getClass().getMethods()) {
                if (m.getName().equals("getPhonemes") && m.getParameterCount() == 1 && m.getParameterTypes()[0].isAssignableFrom(reqClass)) {
                    call = m; break;
                }
            }
            if (call == null) throw new NoSuchMethodException("getPhonemes not found on stub");
            Object resp = call.invoke(stub, req);

            if (fmt == PhonemeFormat.IPA) {
                String ipa = (String) resp.getClass().getMethod("getIpa").invoke(resp);
                if (ipa == null || ipa.isEmpty()) return null;
                List<String> flattened = java.util.Collections.singletonList(ipa);
                List<Sequence> sequences = new ArrayList<>();
                sequences.add(new Sequence(text, flattened));
                return new Result(true, sequences, flattened, java.util.Collections.emptyList(), null);
            } else {
                @SuppressWarnings("unchecked")
                java.util.List<String> ph = (java.util.List<String>) resp.getClass().getMethod("getPhonemesList").invoke(resp);
                if (ph == null || ph.isEmpty()) return null;
                List<Sequence> sequences = new ArrayList<>();
                sequences.add(new Sequence(text, ph));
                List<Integer> ids = new ArrayList<>();
                for (String p : ph) ids.add(phonemeToId(p));
                return new Result(true, sequences, ph, ids, null);
            }
        } catch (Exception e) {
            logger.error("Remote phoneme service failed: {}", e.getMessage());
            return null;
        } finally {
            if (channel != null) try { channel.shutdownNow(); } catch (Exception ignore) {}
        }
    }

    // Preserve punctuation tokens (',', '.', '!', '?') needed by FS2 + HiFiGAN pipeline
    private List<String> tokenizePreservePunct(String text) {
        String normalized = Normalizer.normalize(text, Normalizer.Form.NFKC);
        // Split into words or punctuation tokens
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("[A-Za-z']+|[0-9]+|[,.!?]")
                .matcher(normalized);
        List<String> out = new ArrayList<>();
        while (m.find()) out.add(m.group().toLowerCase(Locale.ROOT));
        return out;
    }

    private static boolean isComma(String tok) { return ",".equals(tok); }
    private static boolean isSentenceEnd(String tok) { return ".".equals(tok) || "!".equals(tok) || "?".equals(tok); }

    // Vocab mapping (prefer phone_id_map.txt; fallback to JSON vocab)
    private volatile Map<String, Integer> vocab;
    private volatile List<String> idToPhoneme;

    private void ensureVocabLoaded() {
        if (vocab != null && idToPhoneme != null) return;
        synchronized (this) {
            if (vocab != null && idToPhoneme != null) return;

            // Try simple text map first (phone_id_map.txt: "TOKEN ID")
            Map<String, Integer> txtMap = tryLoadPhoneIdMapFromConfig();
            if (txtMap != null && !txtMap.isEmpty()) {
                this.vocab = txtMap;
                this.idToPhoneme = invertMapToList(txtMap);
                logger.info("Loaded phone_id_map ({} entries)", txtMap.size());
                return;
            }

            // Fallback to JSON vocab
            Map<String, Integer> map = new HashMap<>();
            List<String> id2 = new ArrayList<>();
            try (java.io.InputStream is = getClass().getClassLoader().getResourceAsStream(
                    ConfigurationManager.getInstance().getString("g2p.phoneme.vocab.resource", "phoneme_vocab.json"))) {
                if (is != null) {
                    com.fasterxml.jackson.databind.JsonNode node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(is);
                    com.fasterxml.jackson.databind.JsonNode p2i = node.get("phoneme_to_id");
                    if (p2i != null) {
                        java.util.Iterator<String> it = p2i.fieldNames();
                        while (it.hasNext()) {
                            String ph = it.next();
                            int id = p2i.get(ph).asInt();
                            map.put(ph, id);
                        }
                    }
                    com.fasterxml.jackson.databind.JsonNode i2p = node.get("id_to_phoneme");
                    if (i2p != null) {
                        int max = -1;
                        java.util.Iterator<String> it = i2p.fieldNames();
                        while (it.hasNext()) {
                            String k = it.next();
                            int idx = Integer.parseInt(k);
                            if (idx > max) max = idx;
                        }
                        for (int i = 0; i <= max; i++) id2.add(null);
                        it = i2p.fieldNames();
                        while (it.hasNext()) {
                            String k = it.next();
                            int idx = Integer.parseInt(k);
                            String ph = i2p.get(k).asText();
                            id2.set(idx, ph);
                        }
                    } else {
                        id2 = invertMapToList(map);
                    }
                }
            } catch (Exception ignore) { }
            this.vocab = map;
            this.idToPhoneme = id2;
            logger.info("Loaded JSON vocab ({} entries)", map.size());
        }
    }

    private Map<String, Integer> tryLoadPhoneIdMapFromConfig() {
        try {
            ConfigurationManager cfg = ConfigurationManager.getInstance();
            String fsPath = cfg.getString("g2p.phone.map.path", "");
            if (fsPath != null && !fsPath.isBlank()) {
                return loadPhoneIdMapFromReader(java.nio.file.Files.newBufferedReader(java.nio.file.Paths.get(fsPath)));
            }
            String resource = cfg.getString("g2p.phone.map.resource", "");
            if (resource != null && !resource.isBlank()) {
                try (java.io.InputStream is = getClass().getClassLoader().getResourceAsStream(resource)) {
                    if (is != null) {
                        return loadPhoneIdMapFromReader(new java.io.BufferedReader(new java.io.InputStreamReader(is)));
                    }
                }
            }
        } catch (Exception e) {
            logger.warn("Failed to load phone_id_map: {}", e.getMessage());
        }
        return null;
    }

    private Map<String, Integer> loadPhoneIdMapFromReader(java.io.BufferedReader br) throws java.io.IOException {
        Map<String, Integer> map = new HashMap<>();
        String line;
        while ((line = br.readLine()) != null) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            String[] parts = line.split("\\s+");
            if (parts.length < 2) continue;
            String token = parts[0];
            int id = Integer.parseInt(parts[1]);
            map.put(token, id);
        }
        return map;
    }

    private List<String> invertToListKeysSortedById(Map<String, Integer> map) {
        int max = -1;
        for (int v : map.values()) if (v > max) max = v;
        List<String> list = new ArrayList<>(Collections.nCopies(max + 1, null));
        for (Map.Entry<String, Integer> e : map.entrySet()) {
            int idx = e.getValue();
            if (idx >= 0 && idx < list.size()) list.set(idx, e.getKey());
        }
        return list;
    }

    private List<String> invertMapToList(Map<String, Integer> map) {
        return invertToListKeysSortedById(map);
    }

    private int phonemeToId(String ph) {
        ensureVocabLoaded();
        if (ph == null) return 0;
        // Try exact token first (handles lowercase specials like 'sp', 'sil', punctuation)
        Integer id = vocab.get(ph);
        if (id == null) {
            // Try uppercase for ARPABET with stress (e.g., AH0, OW1)
            id = vocab.get(ph.toUpperCase(Locale.ROOT));
        }
        if (id == null) {
            // Try lowercase as a last resort
            id = vocab.get(ph.toLowerCase(Locale.ROOT));
        }
        if (id == null) id = vocab.get("<unk>");
        return id != null ? id : 0;
    }

    private List<String> lookupOrGraphemeFallback(String token) {
        // Dictionary lookup (keep stress digits from CMU)
        List<String> dict = cmuDict.get(token);
        if (dict != null) return dict;

        // Naive grapheme-to-phoneme fallback with default stress on vowels to match FS2 ONNX map
        List<String> ph = new ArrayList<>();
        int i = 0;
        while (i < token.length()) {
            // digraphs (lowercase input)
            if (i + 1 < token.length()) {
                String dig = token.substring(i, i + 2);
                switch (dig) {
                    case "sh": ph.add("SH"); i += 2; continue;
                    case "ch": ph.add("CH"); i += 2; continue;
                    case "th": ph.add("TH"); i += 2; continue;
                    case "ph": ph.add("F");  i += 2; continue;
                    case "ng": ph.add("NG"); i += 2; continue;
                    case "qu": ph.add("K"); ph.add("W"); i += 2; continue;
                }
            }
            char c = token.charAt(i);
            switch (c) {
                // Default stressed vowels (common pattern for intelligibility)
                case 'a': ph.add("EY1"); break;
                case 'e': ph.add("IY1"); break;
                case 'i': ph.add("AY1"); break;
                case 'o': ph.add("OW1"); break;
                case 'u': ph.add("UW1"); break;
                // Consonants
                case 'b': ph.add("B"); break;
                case 'c': ph.add("K"); break;
                case 'd': ph.add("D"); break;
                case 'f': ph.add("F"); break;
                case 'g': ph.add("G"); break;
                case 'h': ph.add("HH"); break;
                case 'j': ph.add("JH"); break;
                case 'k': ph.add("K"); break;
                case 'l': ph.add("L"); break;
                case 'm': ph.add("M"); break;
                case 'n': ph.add("N"); break;
                case 'p': ph.add("P"); break;
                case 'q': ph.add("K"); ph.add("W"); break;
                case 'r': ph.add("R"); break;
                case 's': ph.add("S"); break;
                case 't': ph.add("T"); break;
                case 'v': ph.add("V"); break;
                case 'w': ph.add("W"); break;
                case 'x': ph.add("EH1"); ph.add("K"); ph.add("S"); break;
                case 'y': ph.add("Y"); break;
                case 'z': ph.add("Z"); break;
                case '\'': /* ignore apostrophes */ break;
                default: /* ignore */ break;
            }
            i++;
        }
        if (ph.isEmpty()) ph.add("AH0"); // safe fallback with schwa
        return ph;
    }

    private Map<String, List<String>> loadSmallCmuSubset() {
        Map<String, List<String>> m = new HashMap<>();
        // Minimal seed dictionary; retain stress where sensible
        m.put("hello", Arrays.asList("HH", "AH0", "L", "OW1"));
        m.put("world", Arrays.asList("W", "ER1", "L", "D"));
        m.put("voice", Arrays.asList("V", "OY1", "S"));
        m.put("sample", Arrays.asList("S", "AE1", "M", "P", "AH0", "L"));
        m.put("user", Arrays.asList("Y", "UW1", "Z", "ER0"));
        return m;
    }

    private boolean loadCmuFromConfig() {
        try {
            ConfigurationManager cfg = ConfigurationManager.getInstance();
            String fsPath = cfg.getString("g2p.cmu.dict.path", "");
            if (fsPath != null && !fsPath.isBlank()) {
                loadCmuFromReader(java.nio.file.Files.newBufferedReader(java.nio.file.Paths.get(fsPath)));
                logger.info("Loaded CMU dict from path: {} ({} entries)", fsPath, cmuDict.size());
                return true;
            }
            String resource = cfg.getString("g2p.cmu.dict.resource", "");
            if (resource != null && !resource.isBlank()) {
                try (java.io.InputStream is = getClass().getClassLoader().getResourceAsStream(resource)) {
                    if (is != null) {
                        loadCmuFromReader(new java.io.BufferedReader(new java.io.InputStreamReader(is)));
                        logger.info("Loaded CMU dict from resource: {} ({} entries)", resource, cmuDict.size());
                        return true;
                    }
                }
            }
        } catch (Exception e) {
            logger.warn("Failed to load CMU dict: {}", e.getMessage());
        }
        return false;
    }

    private void loadCmuFromReader(java.io.BufferedReader br) throws java.io.IOException {
        String line;
        while ((line = br.readLine()) != null) {
            if (line.isEmpty() || line.startsWith(";;;")) continue;
            int sp = line.indexOf(' ');
            if (sp <= 0) continue;
            String word = line.substring(0, sp).toLowerCase(Locale.ROOT);
            // Handle alt pronunciations like WORD(2)
            int p = word.indexOf('(');
            if (p > 0) word = word.substring(0, p);
            String rest = line.substring(sp).trim();
            if (rest.isEmpty()) continue;
            String[] phones = rest.split("\\s+");
            List<String> lst = new ArrayList<>();
            for (String ph : phones) {
                // KEEP stress digits (e.g., AH0, EH1) to match FS2 phone_id_map
                lst.add(ph.toUpperCase(Locale.ROOT));
            }
            cmuDict.putIfAbsent(word, lst);
        }
    }

    private List<String> expandDigits(String digits) {
        String[] names = {"zero","one","two","three","four","five","six","seven","eight","nine"};
        List<String> out = new ArrayList<>();
        for (char c : digits.toCharArray()) out.add(names[c - '0']);
        return out;
    }

    private Map<String, List<String>> buildArpabetToIpaMap() {
        Map<String, List<String>> m = new HashMap<>();
        m.put("AA", Collections.singletonList("ɑ"));
        m.put("AE", Collections.singletonList("æ"));
        m.put("AH", Collections.singletonList("ʌ"));
        m.put("AO", Collections.singletonList("ɔ"));
        m.put("AW", Arrays.asList("a", "ʊ"));
        m.put("AY", Arrays.asList("a", "ɪ"));
        m.put("B", Collections.singletonList("b"));
        m.put("CH", Collections.singletonList("tʃ"));
        m.put("D", Collections.singletonList("d"));
        m.put("DH", Collections.singletonList("ð"));
        m.put("EH", Collections.singletonList("ɛ"));
        m.put("ER", Collections.singletonList("ɝ"));
        m.put("EY", Arrays.asList("e", "ɪ"));
        m.put("F", Collections.singletonList("f"));
        m.put("G", Collections.singletonList("g"));
        m.put("HH", Collections.singletonList("h"));
        m.put("IH", Collections.singletonList("ɪ"));
        m.put("IY", Collections.singletonList("i"));
        m.put("JH", Collections.singletonList("dʒ"));
        m.put("K", Collections.singletonList("k"));
        m.put("L", Collections.singletonList("l"));
        m.put("M", Collections.singletonList("m"));
        m.put("N", Collections.singletonList("n"));
        m.put("NG", Collections.singletonList("ŋ"));
        m.put("OW", Arrays.asList("o", "ʊ"));
        m.put("OY", Arrays.asList("ɔ", "ɪ"));
        m.put("P", Collections.singletonList("p"));
        m.put("R", Collections.singletonList("ɹ"));
        m.put("S", Collections.singletonList("s"));
        m.put("SH", Collections.singletonList("ʃ"));
        m.put("T", Collections.singletonList("t"));
        m.put("TH", Collections.singletonList("θ"));
        m.put("UH", Collections.singletonList("ʊ"));
        m.put("UW", Collections.singletonList("u"));
        m.put("V", Collections.singletonList("v"));
        m.put("W", Collections.singletonList("w"));
        m.put("Y", Collections.singletonList("j"));
        m.put("Z", Collections.singletonList("z"));
        return m;
    }

    private List<String> toIpa(List<String> arpabet) {
        List<String> out = new ArrayList<>();
        for (String p : arpabet) {
            String base = p.replaceAll("[0-2]$", ""); // strip stress for IPA lookup
            List<String> m = ipaMap.get(base);
            if (m != null) out.addAll(m); else out.add(p.toLowerCase(Locale.ROOT));
        }
        return out;
    }
}