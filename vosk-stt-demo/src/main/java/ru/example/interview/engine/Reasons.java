package ru.example.interview.engine;

/**
 * Человекочитаемые причины решений (для Excel-отчёта; на странице есть такой же словарь).
 *
 * @version 1.0.0
 */
public final class Reasons {
    private Reasons() {}

    /**
     * Преобразует код причины в пользовательский текст.
     *
     * @param reason код причины решения
     * @return строка результата
     */
    public static String text(String reason) {
        if (reason == null) return "";
        return switch (reason) {
            case "sufficient" -> "ответ достаточный — ключевые понятия названы";
            case "needs_detail" -> "не хватало деталей — задан уточняющий вопрос";
            case "ignorance" -> "кандидат сказал, что не знает — не давили";
            case "no_answer" -> "ответа не было";
            case "no_signal" -> "почти ничего не сказано по сути";
            case "off_topic" -> "ответ не по теме вопроса";
            case "follow_up_budget" -> "исчерпан лимит уточняющих вопросов";
            case "stagnation" -> "уточнение ничего не добавило";
            case "no_follow_ups" -> "уточняющие вопросы закончились";
            default -> reason;
        };
    }

    /**
     * Преобразует код действия в пользовательский текст.
     *
     * @param action код действия
     * @return строка результата
     */
    public static String action(String action) {
        if (action == null) return "";
        return switch (action) {
            case "ASK_FOLLOW_UP" -> "уточняющий вопрос";
            case "NEXT_QUESTION" -> "следующий вопрос";
            case "FINISH" -> "конец списка";
            default -> action;
        };
    }
}
