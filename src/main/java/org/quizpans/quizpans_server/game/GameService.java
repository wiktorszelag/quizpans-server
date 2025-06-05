package org.quizpans.quizpans_server.game;

import org.quizpans.quizpans_server.config.DatabaseConfig;
import org.quizpans.quizpans_server.utils.SynonymManager;
import org.quizpans.quizpans_server.utils.TextNormalizer;
import org.apache.commons.text.similarity.LevenshteinDistance;
import org.apache.commons.text.similarity.JaroWinklerSimilarity;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

public class GameService {

    private String currentQuestion;
    private int currentQuestionId = -1;
    private List<AnswerData> currentAnswersList;
    private final String category;
    private Map<String, Set<String>> answerKeyToCombinedKeywords;
    private Map<String, String> synonymToBaseFormMap;

    private static final int N_GRAM_SIZE = 3;
    private static final int MIN_WORDS_FOR_KEYWORD_LOGIC = 2;
    private static final double MIN_KEYWORD_QUALITY_THRESHOLD = 0.35;

    private static final double WEIGHT_LEVENSHTEIN_SIMILARITY = 0.25;
    private static final double WEIGHT_JARO_WINKLER = 0.15;
    private static final double WEIGHT_JACCARD_TOKEN_SET = 0.25;
    private static final double WEIGHT_KEYWORD_SCORE = 0.35;

    private static final double MIN_ACCEPTABLE_COMBINED_SCORE = 0.58;
    private static final double MIN_ACCEPTABLE_PHRASE_SCORE = 0.60;
    private static final double FALLBACK_SINGLE_WORD_JARO_WINKLER_THRESHOLD = 0.85;
    private static final double STRONG_PARTIAL_KEYWORD_CONFIDENCE = 0.75;
    private static final double MIN_COVERAGE_FOR_STRONG_PARTIAL = 0.40;


    public static record AnswerData(String originalText, int points, int displayOrderIndex, String baseForm) {
        public String getOriginalText() { return originalText; }
        public int getPoints() { return points; }
        public int getDisplayOrderIndex() { return displayOrderIndex; }
        public String getBaseForm() { return baseForm; }
    }

    public static class AnswerProcessingResult {
        public final boolean isCorrect;
        public final int pointsAwarded;
        public final String originalAnswerText;
        public final int answerIndex;
        public final String baseFormMatched;

        public AnswerProcessingResult(boolean isCorrect, int pointsAwarded, String originalAnswerText, int answerIndex, String baseFormMatched) {
            this.isCorrect = isCorrect;
            this.pointsAwarded = pointsAwarded;
            this.originalAnswerText = originalAnswerText;
            this.answerIndex = answerIndex;
            this.baseFormMatched = baseFormMatched;
        }
    }

    public GameService(String category) {
        this.category = category;
        this.currentAnswersList = new ArrayList<>();
        this.answerKeyToCombinedKeywords = new HashMap<>();
        this.synonymToBaseFormMap = new HashMap<>();
        if (!TextNormalizer.isLemmatizerInitialized() || !SynonymManager.isLoaded() || !org.quizpans.quizpans_server.utils.SpellCheckerService.isInitialized()) {
            System.err.println("GameService (Server): Krytyczny błąd inicjalizacji narzędzi NLP. Sprawdzanie odpowiedzi może nie działać poprawnie.");
        }
        loadQuestion();
    }

    public String getCurrentQuestion() {
        return currentQuestion;
    }

    public List<AnswerData> getAllAnswersForCurrentQuestion() {
        return Collections.unmodifiableList(currentAnswersList);
    }

    public void loadQuestion() {
        currentAnswersList.clear();
        answerKeyToCombinedKeywords.clear();
        synonymToBaseFormMap.clear();
        currentQuestion = null;
        currentQuestionId = -1;

        try (Connection conn = DriverManager.getConnection(DatabaseConfig.getUrl(), DatabaseConfig.getUser(), DatabaseConfig.getPassword())) {
            String sql;
            PreparedStatement pstmt;

            if (this.category != null && !this.category.trim().isEmpty() && !"MIX (Wszystkie Kategorie)".equalsIgnoreCase(this.category)) {
                sql = "SELECT id, pytanie, odpowiedz1, punkty1, odpowiedz2, punkty2, odpowiedz3, punkty3, odpowiedz4, punkty4, odpowiedz5, punkty5, odpowiedz6, punkty6 FROM Pytania WHERE kategoria = ? ORDER BY RAND() LIMIT 1";
                pstmt = conn.prepareStatement(sql);
                pstmt.setString(1, this.category);
            } else {
                sql = "SELECT id, pytanie, odpowiedz1, punkty1, odpowiedz2, punkty2, odpowiedz3, punkty3, odpowiedz4, punkty4, odpowiedz5, punkty5, odpowiedz6, punkty6 FROM Pytania ORDER BY RAND() LIMIT 1";
                pstmt = conn.prepareStatement(sql);
            }

            ResultSet rs = pstmt.executeQuery();

            if (rs.next()) {
                currentQuestionId = rs.getInt("id");
                currentQuestion = rs.getString("pytanie");

                for (int i = 1; i <= 6; i++) {
                    String answerText = rs.getString("odpowiedz" + i);
                    int points = rs.getInt("punkty" + i);
                    if (answerText != null && !answerText.trim().isEmpty()) {
                        String originalAnswerTrimmed = answerText.trim();
                        String baseForm = TextNormalizer.normalizeToBaseForm(originalAnswerTrimmed);
                        currentAnswersList.add(new AnswerData(originalAnswerTrimmed, points, i - 1, baseForm));

                        List<String> answerTokens = TextNormalizer.getLemmatizedTokens(originalAnswerTrimmed, true);
                        if (answerTokens.size() >= MIN_WORDS_FOR_KEYWORD_LOGIC) {
                            answerKeyToCombinedKeywords.computeIfAbsent(baseForm, k -> new HashSet<>()).addAll(answerTokens);
                        }

                        List<String> rawSynonyms = SynonymManager.findSynonymsFor(originalAnswerTrimmed.toLowerCase());
                        for (String syn : rawSynonyms) {
                            String normalizedSyn = TextNormalizer.normalizeToBaseForm(syn);
                            if (!normalizedSyn.isEmpty() && !normalizedSyn.equals(baseForm) && !currentAnswersList.stream().anyMatch(ad -> ad.baseForm().equals(normalizedSyn))) {
                                synonymToBaseFormMap.put(normalizedSyn, baseForm);
                                List<String> synonymTokens = TextNormalizer.getLemmatizedTokens(syn, true);
                                if (synonymTokens.size() >= MIN_WORDS_FOR_KEYWORD_LOGIC) {
                                    answerKeyToCombinedKeywords.computeIfAbsent(baseForm, k -> new HashSet<>()).addAll(synonymTokens);
                                }
                            }
                        }
                    }
                }
            } else {
                currentQuestion = "Błąd: Brak pytań dla tej kategorii.";
            }
            rs.close();
            pstmt.close();

        } catch (SQLException e) {
            System.err.println("GameService (Server): Błąd SQL podczas ładowania pytania: " + e.getMessage());
            currentQuestion = "Błąd serwera przy ładowaniu pytania.";
            throw new RuntimeException("Błąd SQL podczas ładowania pytania.", e);
        }
    }

    private static Set<String> getCharacterNGrams(String text, int n) {
        Set<String> nGrams = new HashSet<>();
        if (text == null || text.length() < n) return nGrams;
        for (int i = 0; i <= text.length() - n; i++) nGrams.add(text.substring(i, i + n));
        return nGrams;
    }

    private static double calculateJaccardSimilarity(Set<String> set1, Set<String> set2) {
        if (set1 == null || set2 == null) return 0.0;
        if (set1.isEmpty() && set2.isEmpty()) return 1.0;
        if (set1.isEmpty() || set2.isEmpty()) return 0.0;

        Set<String> intersection = new HashSet<>(set1);
        intersection.retainAll(set2);
        Set<String> union = new HashSet<>(set1);
        union.addAll(set2);
        if (union.isEmpty()) return 1.0;
        return (double) intersection.size() / union.size();
    }

    private int calculateAdaptiveLevenshteinThreshold(int correctAnswerLength) {
        if (correctAnswerLength <= 1) return 0;
        if (correctAnswerLength <= 3) return 1;
        if (correctAnswerLength <= 5) return 1;
        if (correctAnswerLength <= 7) return 2;
        if (correctAnswerLength <= 10) return 3;
        return Math.min(4, (int) Math.ceil(correctAnswerLength * 0.30));
    }

    public AnswerProcessingResult processPlayerAnswer(String userAnswerText) {
        if (userAnswerText == null || userAnswerText.trim().isEmpty()) {
            return new AnswerProcessingResult(false, 0, null, -1, null);
        }

        String normalizedUserAnswer = TextNormalizer.normalizeToBaseForm(userAnswerText);
        List<String> userTokens = TextNormalizer.getLemmatizedTokens(userAnswerText, true);

        for (AnswerData correctAnswerData : currentAnswersList) {
            if (correctAnswerData.baseForm().equals(normalizedUserAnswer)) {
                return new AnswerProcessingResult(true, correctAnswerData.points(), correctAnswerData.originalText(), correctAnswerData.displayOrderIndex(), correctAnswerData.baseForm());
            }
        }

        String mappedSynonymBaseForm = synonymToBaseFormMap.get(normalizedUserAnswer);
        if (mappedSynonymBaseForm != null) {
            Optional<AnswerData> synonymTargetAnswer = currentAnswersList.stream()
                    .filter(ad -> ad.baseForm().equals(mappedSynonymBaseForm))
                    .findFirst();
            if (synonymTargetAnswer.isPresent()) {
                return new AnswerProcessingResult(true, synonymTargetAnswer.get().points(), synonymTargetAnswer.get().originalText(), synonymTargetAnswer.get().displayOrderIndex(), synonymTargetAnswer.get().baseForm());
            }
        }


        String bestFuzzyMatchKey = null;
        double highestOverallConfidence = 0.0;
        AnswerData bestMatchData = null;

        JaroWinklerSimilarity jwSimilarity = new JaroWinklerSimilarity();
        LevenshteinDistance levenshteinDistance = new LevenshteinDistance();
        Set<String> userInputNGrams = getCharacterNGrams(normalizedUserAnswer, N_GRAM_SIZE);

        for (AnswerData correctAnswerData : currentAnswersList) {
            String correctBaseForm = correctAnswerData.baseForm();
            if (correctBaseForm.isEmpty()) continue;

            List<String> correctTokens = TextNormalizer.getLemmatizedTokens(correctAnswerData.originalText(), true);

            double keywordScoreContribution = 0.0;
            boolean strongPartialKeywordMatch = false;

            Set<String> expectedKeywords = answerKeyToCombinedKeywords.get(correctBaseForm);
            if (expectedKeywords != null && !expectedKeywords.isEmpty() && !userTokens.isEmpty()) {
                Set<String> userKeywordSet = new HashSet<>(userTokens);
                double tempKeywordScore = 0.0;
                if (expectedKeywords.containsAll(userKeywordSet) && !userKeywordSet.isEmpty()) {
                    double coverage = (double) userKeywordSet.size() / expectedKeywords.size();
                    tempKeywordScore = 0.70 + (0.30 * coverage);
                    if (coverage >= MIN_COVERAGE_FOR_STRONG_PARTIAL && tempKeywordScore >= MIN_KEYWORD_QUALITY_THRESHOLD) {
                        strongPartialKeywordMatch = true;
                    }
                } else {
                    tempKeywordScore = calculateJaccardSimilarity(userKeywordSet, expectedKeywords);
                }
                if (tempKeywordScore >= MIN_KEYWORD_QUALITY_THRESHOLD) {
                    keywordScoreContribution = tempKeywordScore;
                }
            }

            double currentCombinedConfidence = 0.0;
            if (strongPartialKeywordMatch) {
                currentCombinedConfidence = STRONG_PARTIAL_KEYWORD_CONFIDENCE;
            } else {
                int ld = levenshteinDistance.apply(normalizedUserAnswer, correctBaseForm);
                int adaptiveLevThreshold = calculateAdaptiveLevenshteinThreshold(correctBaseForm.length());

                if (ld <= adaptiveLevThreshold) {
                    double levSimString = 0.0;
                    int maxLengthString = Math.max(normalizedUserAnswer.length(), correctBaseForm.length());
                    if (maxLengthString > 0) levSimString = 1.0 - ((double) ld / maxLengthString);
                    else if (ld == 0) levSimString = 1.0;

                    double jwScoreString = (normalizedUserAnswer.length() >=1 && correctBaseForm.length() >=1) ? jwSimilarity.apply(normalizedUserAnswer, correctBaseForm) : 0.0;
                    double tokenSetJaccardScore = calculateJaccardSimilarity(new HashSet<>(userTokens), new HashSet<>(correctTokens));

                    currentCombinedConfidence = (WEIGHT_LEVENSHTEIN_SIMILARITY * levSimString) +
                            (WEIGHT_JARO_WINKLER * jwScoreString) +
                            (WEIGHT_JACCARD_TOKEN_SET * tokenSetJaccardScore) +
                            (WEIGHT_KEYWORD_SCORE * keywordScoreContribution);
                }
            }

            if (currentCombinedConfidence > highestOverallConfidence) {
                highestOverallConfidence = currentCombinedConfidence;
                bestFuzzyMatchKey = correctBaseForm;
                bestMatchData = correctAnswerData;
            }
        }

        double effectiveThreshold = (userTokens.size() > 1 || (bestFuzzyMatchKey != null && bestFuzzyMatchKey.contains(" "))) ? MIN_ACCEPTABLE_PHRASE_SCORE : MIN_ACCEPTABLE_COMBINED_SCORE;

        if (bestMatchData != null && highestOverallConfidence >= effectiveThreshold) {
            return new AnswerProcessingResult(true, bestMatchData.points(), bestMatchData.originalText(), bestMatchData.displayOrderIndex(), bestMatchData.baseForm());
        }

        if (userTokens.size() == 1 && (bestMatchData == null || highestOverallConfidence < effectiveThreshold)) {
            for (AnswerData correctAnswerData : currentAnswersList) {
                if (correctAnswerData.baseForm().split("\\s+").length == 1) {
                    double jwFallback = jwSimilarity.apply(normalizedUserAnswer, correctAnswerData.baseForm());
                    if (jwFallback >= FALLBACK_SINGLE_WORD_JARO_WINKLER_THRESHOLD) {
                        return new AnswerProcessingResult(true, correctAnswerData.points(), correctAnswerData.originalText(), correctAnswerData.displayOrderIndex(), correctAnswerData.baseForm());
                    }
                }
            }
        }
        return new AnswerProcessingResult(false, 0, null, -1, null);
    }
}