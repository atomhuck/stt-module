package ru.example.interview.engine;

/**
 * Результат разбора одного ответа (покрытие — накопительное по всем попыткам внутри вопроса).
 *
 * @param words        число слов
 * @param techWeight   суммарный IDF технических токенов
 * @param onTopicWeight из них относящихся к теме вопроса
 * @param coverage     доля (по весу) ключевых понятий эталона, уже названных, 0..1
 * @param coreCoverage то же для «ядра» понятий
 * @param termTokens   сколько раз использованы термины/сленг
 * @param glossaryIds  различные термины/сленг из словаря, использованные в ответе
 * @version 1.0.0
 */
public record AnswerAnalysis(int words, double techWeight, double onTopicWeight, double coverage, double coreCoverage,
                             int newHits, int hedgeCount, boolean ignorance, int termTokens, int[] glossaryIds) {
}
