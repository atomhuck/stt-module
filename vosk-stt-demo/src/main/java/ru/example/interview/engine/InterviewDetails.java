package ru.example.interview.engine;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Полные данные собеседования для отчёта (Excel): вопросы, ответы, что прозвучало и не прозвучало, оценки.
 * Содержит эталонные ответы — поэтому в браузер кандидата НЕ отправляется (туда уходит только InterviewReport).
 * @version 1.0.0
 */
public record InterviewDetails(String candidate, String levels, Instant startedAt, Instant finishedAt,
                               boolean finishedEarly, int plannedQuestions, int answeredQuestions,
                               InterviewReport report, List<QuestionDetail> questions,
                               List<String> termsUsed, List<String> slangUsed, Map<String, String> settings) {

    /**
     * Сохраняет данные одного вопроса для итогового отчёта.
     *
     * @version 1.0.0
     */
    public record QuestionDetail(int number, String level, String topic, String question, String reference,
                                 String criteria, List<FollowUpInfo> followUps, List<AttemptDetail> attempts,
                                 List<ConceptDetail> concepts, double coverage,
                                 double completeness, double technical, double terminology, double speed,
                                 List<String> termsUsed, List<String> slangUsed, int hedgeCount, int words,
                                 String comment) {}

    /**
     * Показывает, был ли задан уточняющий вопрос.
     *
     * @version 1.0.0
     */
    public record FollowUpInfo(String text, boolean asked) {}

    /**
     * @param wpm темп речи, слов/мин (null — речь слишком короткая, чтобы оценить)
     * @version 1.0.0
     */
    public record AttemptDetail(boolean followUp, int followUpNumber, String prompt, String answer, boolean answered,
                                String source, int words, long latencyMs, long speechMs, Double wpm,
                                double speedScore, double coverageAfter, String action, String reason,
                                Instant answeredAt) {}

    /**
     * Сохраняет покрытие отдельного понятия ответа.
     *
     * @version 1.0.0
     */
    public record ConceptDetail(String label, double weight, boolean core, boolean said) {}
}
