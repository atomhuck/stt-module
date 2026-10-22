package ru.example.interview.engine;

/**
 * Вопрос, «предкомпилированный» при старте: ключевые понятия эталона в виде int-массивов,
 * чтобы разбор ответа во время интервью не делал ни поиска по строкам, ни аллокаций.
 *
 * @param poolIds          id ключевых понятий эталона (в порядке убывания веса)
 * @param poolW            веса понятий (IDF × коэффициенты)
 * @param core             true для «ядра» — самых весомых понятий
 * @param poolIndex        id словаря → индекс в pool или -1
 * @param topic            id словаря → «относится к теме вопроса»
 * @param followUps        уточняющие вопросы с привязкой к понятиям пула
 * @param glossaryInPool   сколько терминов/сленга среди понятий пула
 * @version 1.0.0
 */
public record CompiledQuestion(Question question, int[] poolIds, double[] poolW, boolean[] core, double poolTotalW,
                               double coreTotalW, int[] poolIndex, boolean[] topic, FollowUp[] followUps,
                               int refWords, int targetWords, int glossaryInPool) {

    /**
     * Содержит текст уточнения и индексы понятий, которые оно проверяет.
     *
     * @version 1.0.0
     */
    public record FollowUp(String text, int[] poolIdx) {}

    /**
     * Возвращает текущее значение без изменения состояния.
     *
     * @return целочисленное значение
     */
    public int id() {
        return question.id();
    }

    /**
     * Возвращает текущее значение без изменения состояния.
     *
     * @return результат операции
     */
    public Level level() {
        return question.level();
    }

    /**
     * Возвращает текущее значение без изменения состояния.
     *
     * @return целочисленное значение
     */
    public int poolSize() {
        return poolIds.length;
    }
}
