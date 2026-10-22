package ru.example.interview.engine;

/**
 * Решение «уточнить или идти дальше». Работает только с уже посчитанными числами — O(число уточняющих вопросов).
 * @version 1.0.0
 */
public final class DecisionPolicy {

    /**
     * Хранит действие по ответу и причину выбора.
     *
     * @version 1.0.0
     */
    public record Decision(boolean followUp, String reason, int followUpIndex) {}

    private final int maxFollowUps;
    private final double weakCoverage;

    /**
     * Создаёт объект с указанными зависимостями и настройками.
     *
     * @param maxFollowUps параметр операции
     * @param weakCoverage параметр операции
     */
    public DecisionPolicy(int maxFollowUps, double weakCoverage) {
        this.maxFollowUps = maxFollowUps;
        this.weakCoverage = weakCoverage;
    }

    /**
     * Выбирает продолжение интервью по покрытию эталона и лимиту уточнений.
     *
     * @param q скомпилированный вопрос
     * @param level уровень вопроса
     * @param a анализ ответа
     * @param prevCoverage покрытие предыдущей попытки
     * @param followUpsUsed параметр операции
     * @param asked параметр операции
     * @param hits параметр операции
     * @return выбранное действие и его причина
     */
    public Decision decide(CompiledQuestion q, Level level, AnswerAnalysis a, double prevCoverage,
                           int followUpsUsed, boolean[] asked, boolean[] hits) {
        if (a.words() == 0) {
            return next("no_answer");
        }
        if (a.ignorance() && a.coverage() < weakCoverage) {
            return next("ignorance");               // «не знаю» — не давим
        }
        if (a.coverage() >= level.sufficientCoverage) {
            return next("sufficient");              // ключевые понятия названы
        }
        if (followUpsUsed >= maxFollowUps) {
            return next("follow_up_budget");
        }
        double focus = a.techWeight() > 0 ? a.onTopicWeight() / a.techWeight() : 1.0;
        if (a.coverage() < weakCoverage && focus < 0.25) {
            return next("off_topic");               // уверенно говорит, но про другое — уточнять нечего
        }
        if (a.coverage() < weakCoverage && a.words() < 6) {
            return next("no_signal");               // почти ничего не сказано
        }
        if (followUpsUsed >= 1 && a.coverage() - prevCoverage < 0.03) {
            return next("stagnation");              // уточнение ничего не добавило
        }
        int best = -1;
        double bestScore = -1;
        CompiledQuestion.FollowUp[] fus = q.followUps();
        for (int j = 0; j < fus.length; j++) {
            if (asked[j]) continue;
            double s = 0;
            for (int p : fus[j].poolIdx()) {
                if (!hits[p]) s += q.poolW()[p];    // уточнение должно бить в то, что ещё не названо
            }
            if (s > bestScore) {
                bestScore = s;
                best = j;
            }
        }
        if (best < 0) {
            return next("no_follow_ups");
        }
        return new Decision(true, "needs_detail", best);
    }

    private static Decision next(String reason) {
        return new Decision(false, reason, -1);
    }
}
