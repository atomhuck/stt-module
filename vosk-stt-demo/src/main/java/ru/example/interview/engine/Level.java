package ru.example.interview.engine;

 * @version 1.0.0
/** Уровень вопроса. sufficientCoverage — доля ключевых понятий эталона, после которой ответ считается достаточным. */
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
     * @param s параметр операции
     * @return результат операции
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
