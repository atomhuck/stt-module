package ru.example.interview.engine;

/**
 * Уровень вопроса. sufficientCoverage — доля ключевых понятий эталона, после которой ответ считается достаточным.
 *
 * @version 1.0.0
 */
public enum Level {
    INTERN("Стажёр", 0.40),
    JUNIOR("Junior", 0.45),
    MIDDLE("Middle", 0.50),
    SENIOR("Senior", 0.55);

    public final String title;
    public final double sufficientCoverage;

    Level(String title, double sufficientCoverage) {
        this.title = title;
        this.sufficientCoverage = sufficientCoverage;
    }

    /**
     * Преобразует русское или английское название уровня в значение перечисления.
     *
     * @param s строковое название уровня
     * @return соответствующий уровень интервью
     */
    public static Level parse(String s) {
        String t = s.trim().toLowerCase().replace('ё', 'е');
        return switch (t) {
            case "стажер", "intern" -> INTERN;
            case "junior", "джуниор" -> JUNIOR;
            case "middle", "миддл", "мидл" -> MIDDLE;
            case "senior", "сеньор" -> SENIOR;
            default -> throw new IllegalArgumentException("Неизвестный уровень: " + s);
        };
    }
}
