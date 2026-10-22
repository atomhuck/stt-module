package ru.example.interview.engine;

import java.util.List;

/**
 * Хранит вопрос, эталон и варианты уточнений.
 *
 * @version 1.0.0
 */
public record Question(int id, Level level, String topic, String text, String reference, String notes,
                       List<String> followUps) {
}
