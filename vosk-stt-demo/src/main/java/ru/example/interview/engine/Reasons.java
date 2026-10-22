package ru.example.interview.engine;

 * @version 1.0.0
/** Человекочитаемые причины решений (для Excel-отчёта; на странице есть такой же словарь). */
public final class Reasons {
    private Reasons() {}

    /**
     * Выполняет операцию публичного контракта компонента.
     *
     * @param reason параметр операции
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
     * Выполняет операцию публичного контракта компонента.
     *
     * @param action параметр операции
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
