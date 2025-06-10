package org.quizpans.quizpans_server.utils;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class SynonymManager {
    private static final String THESAURUS_PATH = "/thesaurus.txt";
    private static final Map<String, List<String>> synonymDictionary = new ConcurrentHashMap<>();
    private static boolean isLoaded = false;

    static {
        loadThesaurus();
    }

    private static synchronized void loadThesaurus() {
        if (isLoaded) return;

        try (InputStream is = SynonymManager.class.getResourceAsStream(THESAURUS_PATH)) {
            if (is == null) {
                throw new NullPointerException("Nie można znaleźć pliku tezaurusa w classpath: " + THESAURUS_PATH);
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    processDictionaryLine(line);
                }
                isLoaded = true;
            }
        } catch (Exception e) {
            System.err.println("SynonymManager (Server): Błąd podczas ładowania tezaurusa z " + THESAURUS_PATH + ": " + e.getMessage());
            e.printStackTrace();
            isLoaded = false;
        }
    }

    private static void processDictionaryLine(String line) {
        String[] lineParts = line.split("#", 2);
        String relevantPart = lineParts[0].trim();
        relevantPart = relevantPart.replaceAll("^;+|;+$", "");

        if (relevantPart.isEmpty()) return;

        String[] parts = relevantPart.split(";", -1);
        if (parts.length < 1) return;

        String mainWordKey = parts[0].trim().toLowerCase();

        if (mainWordKey.isEmpty()) {
            return;
        }

        List<String> synonyms = new ArrayList<>();
        for (int i = 1; i < parts.length; i++) {
            String synonym = parts[i].trim().toLowerCase();
            if (!synonym.isEmpty()) {
                synonyms.add(synonym);
            }
        }

        if (!synonymDictionary.containsKey(mainWordKey)) {
            synonymDictionary.put(mainWordKey, Collections.unmodifiableList(synonyms));
        } else {
            List<String> existingSynonyms = new ArrayList<>(synonymDictionary.get(mainWordKey));
            Set<String> uniqueSynonyms = new HashSet<>(existingSynonyms);
            uniqueSynonyms.addAll(synonyms);
            synonymDictionary.put(mainWordKey, Collections.unmodifiableList(new ArrayList<>(uniqueSynonyms)));
        }
    }

    public static List<String> findSynonymsFor(String word) {
        if (!isLoaded) {
            return Collections.emptyList();
        }
        String searchKey = word.trim().toLowerCase();
        return synonymDictionary.getOrDefault(searchKey, Collections.emptyList());
    }

    public static boolean isLoaded() {
        return isLoaded;
    }
}