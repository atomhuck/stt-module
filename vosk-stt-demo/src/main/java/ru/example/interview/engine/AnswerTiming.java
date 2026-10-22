package ru.example.interview.engine;

/**
 * @param latencyMs время от момента, когда вопрос озвучен/показан, до начала речи
 * @param speechMs  длительность речи (от первого до последнего голосового кадра)
 * @version 1.0.0
 */
public record AnswerTiming(long latencyMs, long speechMs) {
}
