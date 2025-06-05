package org.quizpans.quizpans_server.utils;

import opennlp.tools.lemmatizer.LemmatizerME;
import opennlp.tools.lemmatizer.LemmatizerModel;
import opennlp.tools.tokenize.SimpleTokenizer;
import java.io.InputStream;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public class NLPProcessor {
    private static final Set<String> STOP_WORDS = Set.of("i", "lub", "czy", "a", "w", "na", "do", "się");
    private final LemmatizerME lemmatizer;

    public NLPProcessor() {
        try (InputStream modelStream = getClass().getClassLoader().getResourceAsStream("/opennlp-pl-ud-pdb-lemmas-1.2-2.5.0.bin")) {
            if (modelStream == null) {
                throw new RuntimeException("Nie można znaleźć pliku modelu lematyzatora: opennlp-pl-ud-pdb-lemmas-1.2-2.5.0.bin w classpath");
            }
            LemmatizerModel model = new LemmatizerModel(modelStream);
            this.lemmatizer = new LemmatizerME(model);
        } catch (Exception e) {
            throw new RuntimeException("Błąd inicjalizacji procesora NLP", e);
        }
    }

    public List<String> processText(String text) {
        String[] tokens = SimpleTokenizer.INSTANCE.tokenize(text);
        String[] lemmas = lemmatizer.lemmatize(tokens, new String[tokens.length]);
        return Arrays.stream(lemmas)
                .filter(lemma -> !STOP_WORDS.contains(lemma))
                .collect(Collectors.toList());
    }
}