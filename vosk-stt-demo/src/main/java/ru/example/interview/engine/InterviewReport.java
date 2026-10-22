package ru.example.interview.engine;

import java.util.List;

 * @version 1.0.0
/** Итог собеседования. Все оценки — по 10-балльной шкале, итог — среднее четырёх критериев (≤ 10, 2 знака). */
public record InterviewReport(double completeness, double technical, double terminology, double speed,
                              double total, String totalText, String verdict,
                              int plannedQuestions, int answeredQuestions, boolean finishedEarly,
                              List<QuestionResult> questions) {

    /**
     * Содержит результат одного вопроса.
     *
     * @version 1.0.0
     */
    public record QuestionResult(int number, String level, String topic, String question, int attempts,
                                 int coveragePercent, double completeness, double technical, double terminology,
                                 double speed, List<String> missing) {}
}
