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
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;
//zarzadzanie stanem gry
// ladowanie pytan z DatabesConfig
// ocena odpowiedzi
public class GameService {
        //przechowanie odpowiedzi
    private String currentQuestion;
    private int currentQuestionId = -1;
    private List<AnswerData> currentAnswersList;
    private final String category;
    private Map<String, Set<String>> answerKeyToCombinedKeywords;
    private Map<String, String> synonymToBaseFormMap;
            //mapy do slow
    private static final int N_GRAM_SIZE = 3;
    private static final int MIN_WORDS_FOR_KEYWORD_LOGIC = 2;
    private static final double MIN_KEYWORD_QUALITY_THRESHOLD = 0.35;
            //wagi do algo
    private static final double WEIGHT_LEVENSHTEIN_SIMILARITY = 0.25;
    private static final double WEIGHT_JARO_WINKLER = 0.15;
    private static final double WEIGHT_JACCARD_TOKEN_SET = 0.25;
    private static final double WEIGHT_KEYWORD_SCORE = 0.35;
            //progi
    private static final double MIN_ACCEPTABLE_COMBINED_SCORE = 0.58;
    private static final double MIN_ACCEPTABLE_PHRASE_SCORE = 0.60;
    private static final double FALLBACK_SINGLE_WORD_JARO_WINKLER_THRESHOLD = 0.85;
    private static final double STRONG_PARTIAL_KEYWORD_CONFIDENCE = 0.75;
    private static final double MIN_COVERAGE_FOR_STRONG_PARTIAL = 0.40;

        //odpowiedz teskt punkty indeks +forma pordsawowa
    public static record AnswerData(String originalText, int points, int displayOrderIndex, String baseForm) {
        public String getOriginalText() { return originalText; }
        public int getPoints() { return points; }
        public int getDisplayOrderIndex() { return displayOrderIndex; }
        public String getBaseForm() { return baseForm; }
    }
        //gracz odpowiedzi punkty
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
                //kateogira gry przechowywanie
    public GameService(String category) {
        this.category = category;
        this.currentAnswersList = new ArrayList<>();
        this.answerKeyToCombinedKeywords = new HashMap<>();
        this.synonymToBaseFormMap = new HashMap<>();
    }
            //stan gry
    public String getCurrentQuestion() {
        return currentQuestion;
    }

    public int getCurrentQuestionId() {
        return currentQuestionId;
    }

    public int getTotalAnswersCount() {
        return currentAnswersList.size();
    }

    public List<AnswerData> getAllAnswersForCurrentQuestion() {
        return Collections.unmodifiableList(currentAnswersList);
    }
//reste
    public boolean loadQuestion(Set<Integer> idsToExclude) {
        currentAnswersList.clear();
        answerKeyToCombinedKeywords.clear();
        synonymToBaseFormMap.clear();
        currentQuestion = null;
        currentQuestionId = -1;
//pytanie bez questions
        try (Connection conn = DriverManager.getConnection(DatabaseConfig.getUrl(), DatabaseConfig.getUser(), DatabaseConfig.getPassword())) {
            boolean questionLoaded = tryLoadQuestion(conn, idsToExclude);

            if (!questionLoaded && idsToExclude != null && !idsToExclude.isEmpty()) {
                questionLoaded = tryLoadQuestion(conn, Collections.emptySet());
                if (questionLoaded) {
                    return true;
                }
            }

            if (!questionLoaded) {
                currentQuestion = null;
                currentQuestionId = -1;
                return false;
            }
            return true;

        } catch (SQLException e) {
            currentQuestion = "Błąd serwera przy ładowaniu pytania.";
            throw new RuntimeException("Błąd SQL podczas ładowania pytania.", e);
        }
    }
            // losowe pytanie + pelne dane
    private boolean tryLoadQuestion(Connection conn, Set<Integer> idsToExclude) throws SQLException {
        List<Integer> availableQuestionIds = new ArrayList<>();
        StringBuilder sqlBuilder = new StringBuilder("SELECT id FROM Pytania");
        List<Object> params = new ArrayList<>();
        boolean hasWhere = false;

        if (this.category != null && !this.category.trim().isEmpty() && !"MIX (Wszystkie Kategorie)".equalsIgnoreCase(this.category)) {
            sqlBuilder.append(" WHERE kategoria = ?");
            params.add(this.category);
            hasWhere = true;
        }

        if (idsToExclude != null && !idsToExclude.isEmpty()) {
            if (!hasWhere) {
                sqlBuilder.append(" WHERE");
            } else {
                sqlBuilder.append(" AND");
            }
            String placeholders = idsToExclude.stream().map(id -> "?").collect(Collectors.joining(","));
            sqlBuilder.append(" id NOT IN (").append(placeholders).append(")");
            params.addAll(idsToExclude);
        }

        try (PreparedStatement pstmt = conn.prepareStatement(sqlBuilder.toString())) {
            for(int i=0; i < params.size(); i++) {
                pstmt.setObject(i+1, params.get(i));
            }
            try (ResultSet rs = pstmt.executeQuery()) {
                while(rs.next()) {
                    availableQuestionIds.add(rs.getInt("id"));
                }
            }
        }

        if (availableQuestionIds.isEmpty()) {
            return false;
        }

        int randomId = availableQuestionIds.get(new Random().nextInt(availableQuestionIds.size()));

        String selectSql = "SELECT * FROM Pytania WHERE id = ?";
        try (PreparedStatement pstmtSelect = conn.prepareStatement(selectSql)) {
            pstmtSelect.setInt(1, randomId);
            try (ResultSet rs = pstmtSelect.executeQuery()) {
                if (rs.next()) {
                    currentQuestionId = rs.getInt("id");
                    currentQuestion = rs.getString("pytanie");
// przetwarzanie kolejnych pytan
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
                    return true;
                }
            }
        }
        return false;
    }

  //analiza odpowiedzi
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
    //metoda Levenshteina
    private int calculateAdaptiveLevenshteinThreshold(int correctAnswerLength) {
        if (correctAnswerLength <= 1) return 0;
        if (correctAnswerLength <= 3) return 1;
        if (correctAnswerLength <= 5) return 1;
        if (correctAnswerLength <= 7) return 2;
        if (correctAnswerLength <= 10) return 3;
        return Math.min(4, (int) Math.ceil(correctAnswerLength * 0.30));
    }
// przetwarzanie odpowiedzi
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
        //synonimy
        String mappedSynonymBaseForm = synonymToBaseFormMap.get(normalizedUserAnswer);
        if (mappedSynonymBaseForm != null) {
            Optional<AnswerData> synonymTargetAnswer = currentAnswersList.stream()
                    .filter(ad -> ad.baseForm().equals(mappedSynonymBaseForm))
                    .findFirst();
            if (synonymTargetAnswer.isPresent()) {
                return new AnswerProcessingResult(true, synonymTargetAnswer.get().points(), synonymTargetAnswer.get().originalText(), synonymTargetAnswer.get().displayOrderIndex(), synonymTargetAnswer.get().baseForm());
            }
        }

//fuzy dopaoswywanie
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
//oblicznie wyniku
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
        //blad
        return new AnswerProcessingResult(false, 0, null, -1, null);
    }
}