package org.quizpans.quizpans_server.utils;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.ArrayList;

public class TextNormalizer {
    private static NLPProcessor nlpProcessorInstance;
    private static boolean nlpProcessorInitialized = false;

    private static final Map<String, String> normalizationCacheSingleString = new ConcurrentHashMap<>();
    private static final Map<String, List<String>> normalizationCacheTokenList = new ConcurrentHashMap<>();

    private static final Set<String> STOP_WORDS = Set.of(
            "ach", "aj", "albo", "ależ", "aż", "bardziej", "bardzo", "bez", "bo", "bowiem", "by", "byli", "bym", "byś", "był", "była", "było",
            "ci", "cię", "ciebie", "co", "coś", "czy", "czyli", "daleko", "dla", "dlaczego", "do", "dobrze", "dokąd", "dość", "dużo", "dwa", "dwaj", "dwie",
            "dzisiaj", "dziś", "gdy", "gdyby", "gdyż", "gdzie", "go", "i", "ich", "ile", "im", "inny", "ja", "ją", "jak", "jakaś", "jakby", "jaki", "jakiś",
            "jakie", "jako", "jakoś", "je", "jeden", "jedna", "jedno", "jego", "jej", "jemu", "jeszcze", "jest", "jestem", "jeśli", "jeżeli", "już",
            "każdy", "kiedy", "kierunku", "kto", "ktoś", "która", "które", "którego", "której", "który", "których", "którym", "którzy", "ku",
            "lecz", "lub", "ma", "mają", "mam", "mi", "mną", "mnie", "moi", "musi", "mój", "moja", "moje", "my",
            "na", "nad", "nam", "nami", "nas", "nasi", "nasz", "nasza", "nasze", "natychmiast", "nawet", "nią", "nic", "nich", "nie", "niego", "niej", "niemu", "nigdy", "nim", "nimi", "niż", "no",
            "o", "obok", "od", "około", "on", "ona", "one", "oni", "ono", "oraz", "oto",
            "pan", "po", "pod", "podczas", "pomimo", "ponad", "ponieważ", "przed", "przede", "przedtem", "przez", "przy",
            "razie", "roku", "również",
            "się", "skąd", "sobie", "sobą", "sposób", "swoje", "są",
            "ta", "tak", "taka", "taki", "takie", "także", "tam", "te", "tego", "tej", "temu", "ten", "teraz", "też", "to", "tobą", "tobie", "trzy", "tu", "tutaj", "twoi", "twój", "twoja", "twoje", "ty", "tym", "tys",
            "w", "wam", "wami", "was", "wasi", "wasz", "wasza", "wasze", "we", "więc", "wszyscy", "wszystkich", "wszystkie", "wszystkim", "wszystko", "wtedy",
            "z", "za", "żaden", "żadna", "żadne", "żadnych", "że", "żeby"
    );

    static {
        try {
            nlpProcessorInstance = new NLPProcessor();
            nlpProcessorInitialized = true;
        } catch (Throwable e) {
            System.err.println("Krytyczny błąd podczas inicjalizacji NLPProcessor w TextNormalizer (Server): " + e.getMessage());
            e.printStackTrace();
        }

        if (!SpellCheckerService.isInitialized()) {
            System.err.println("Ostrzeżenie TextNormalizer (Server): SpellCheckerService nie został poprawnie zainicjalizowany.");
        }
    }

    public static boolean isLemmatizerInitialized() {
        return nlpProcessorInitialized;
    }

    private static List<String> lemmatizeAndClean(String text, boolean removeStopWords) {
        if (text == null || text.trim().isEmpty()) {
            return new ArrayList<>();
        }

        String spellingCorrectedText = SpellCheckerService.correctPhrase(text.trim());

        String cleanedTextForTokens = spellingCorrectedText.toLowerCase()
                .replace("ł", "l").replace("ś", "s").replace("ż", "z")
                .replace("ź", "z").replace("ć", "c").replace("ń", "n")
                .replace("ą", "a").replace("ę", "e").replace("ó", "o")
                .replaceAll("[^a-z0-9\\s]", "").replaceAll("\\s+", " ").trim();

        if (cleanedTextForTokens.isEmpty()) {
            return new ArrayList<>();
        }

        List<String> finalTokens;
        if (nlpProcessorInitialized && nlpProcessorInstance != null) {
            finalTokens = nlpProcessorInstance.processText(cleanedTextForTokens);
        } else {
            System.err.println("TextNormalizer (Server): NLPProcessor nie jest zainicjalizowany. Używanie tokenów bez lematyzacji.");
            finalTokens = Arrays.asList(cleanedTextForTokens.split("\\s+"));
        }

        if (removeStopWords) {
            return finalTokens.stream()
                    .filter(token -> !STOP_WORDS.contains(token) && !token.isEmpty())
                    .collect(Collectors.toList());
        }
        return finalTokens.stream().filter(token -> !token.isEmpty()).collect(Collectors.toList());
    }

    public static String normalizeToBaseForm(String text) {
        if (text == null || text.trim().isEmpty()) {
            return "";
        }
        String cacheKey = text.trim() + "_stopwords:true_spellcheck:LT_lemmatizer:" + nlpProcessorInitialized;
        return normalizationCacheSingleString.computeIfAbsent(cacheKey, key -> {
            List<String> lemmas = lemmatizeAndClean(text.trim(), true);
            return String.join("", lemmas);
        });
    }

    public static List<String> getLemmatizedTokens(String text, boolean removeStopWords) {
        if (text == null || text.trim().isEmpty()) {
            return new ArrayList<>();
        }
        String cacheKey = text.trim() + "_stopwords:" + removeStopWords + "_spellcheck:LT_lemmatizer:" + nlpProcessorInitialized;
        return normalizationCacheTokenList.computeIfAbsent(cacheKey, k ->
                lemmatizeAndClean(text.trim(), removeStopWords)
        );
    }

    public static void clearCache() {
        normalizationCacheSingleString.clear();
        normalizationCacheTokenList.clear();
    }
}