package org.quizpans.quizpans_server.utils;

import org.languagetool.JLanguageTool;
import org.languagetool.language.Polish;
import org.languagetool.rules.RuleMatch;

import java.io.IOException;
import java.util.List;
import java.util.stream.Collectors;

public class SpellCheckerService {

    private static JLanguageTool langTool;
    private static boolean isInitialized = false;

    static {
        try {
            langTool = new JLanguageTool(new Polish());
            isInitialized = true;
        } catch (Exception e) {
            System.err.println("Krytyczny błąd podczas inicjalizacji LanguageTool (Server): " + e.getMessage());
            e.printStackTrace();
            isInitialized = false;
        }
    }

    public static boolean isInitialized() {
        return isInitialized;
    }

    public static String correctWord(String word) {
        if (!isInitialized || word == null || word.trim().isEmpty()) {
            return word;
        }
        String trimmedWord = word.trim();
        try {
            List<RuleMatch> matches = langTool.check(trimmedWord);
            for (RuleMatch match : matches) {
                if (!match.getSuggestedReplacements().isEmpty()) {
                    String suggestion = match.getSuggestedReplacements().get(0);
                    if (suggestion != null && !suggestion.isEmpty()) {
                        return suggestion;
                    }
                }
            }
        } catch (IOException e) {
            System.err.println("Błąd podczas sprawdzania pisowni z LanguageTool (Server) dla słowa '" + trimmedWord + "': " + e.getMessage());
        }
        return trimmedWord;
    }

    public static String correctPhrase(String phrase) {
        if (!isInitialized || phrase == null || phrase.trim().isEmpty()) {
            return phrase;
        }
        String[] words = phrase.split("\\s+");
        return java.util.Arrays.stream(words)
                .map(SpellCheckerService::correctWord)
                .collect(Collectors.joining(" "));
    }
}