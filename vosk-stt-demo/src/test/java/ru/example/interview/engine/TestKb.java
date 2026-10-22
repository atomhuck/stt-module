package ru.example.interview.engine;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** Загрузка банка вопросов и словаря из classpath для тестов. */
public final class TestKb {
    private static InterviewKnowledge kb;
    private static InterviewKnowledge single;

    private TestKb() {}

    public static synchronized InterviewKnowledge get() {
        if (kb == null) {
            kb = InterviewKnowledge.build(QuestionBank.parse(read("/interview/questions.tsv")),
                    Glossary.parse(read("/interview/glossary.txt")))
                    .withSpoken(SpokenText.parse(read("/interview/spoken.txt")));
        }
        return kb;
    }

    /** База из ОДНОГО вопроса (первого): удобно проверять конец интервью. */
    public static synchronized InterviewKnowledge single() {
        if (single == null) {
            single = InterviewKnowledge.build(QuestionBank.parse(read("/interview/questions.tsv")).subList(0, 1),
                    Glossary.parse(read("/interview/glossary.txt")))
                    .withSpoken(SpokenText.parse(read("/interview/spoken.txt")));
        }
        return single;
    }

    public static String read(String path) {
        try (InputStream in = TestKb.class.getResourceAsStream(path)) {
            if (in == null) throw new IllegalStateException("Нет ресурса " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
