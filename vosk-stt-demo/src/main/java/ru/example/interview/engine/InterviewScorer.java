package ru.example.interview.engine;

import ru.example.interview.engine.InterviewSession.Attempt;
import ru.example.interview.engine.InterviewSession.QuestionRun;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Итоговая аналитика по 4 критериям (каждый 0..10):
 * 1) развёрнутость и полнота, 2) техническая грамотность, 3) владение терминологией/сленгом, 4) скорость ответа.
 * Скорость = 0.6·быстрота старта + 0.4·темп речи, умноженная на коэффициент содержательности (0.3…1).
 * Итог = (c1 + c2 + c3 + c4) / 4 — то есть сумма, нормированная к максимуму 10 баллов, округление до 0.01.
 * @version 1.0.0
 */
public final class InterviewScorer {

    static final double GRACE_MS = 1500;          // время на осмысление без штрафа
    static final double LATENCY_FALLOFF_MS = 18000; // за сколько мс после grace оценка падает до 0
    static final double TARGET_TERM_DENSITY = 12;   // терминов на 100 слов
    static final double TARGET_SLANG_DISTINCT = 3;  // различных слов сленга за интервью
    public static final double SLANG_TARGET = TARGET_SLANG_DISTINCT;

    private InterviewScorer() {}

    /** Оценка одного вопроса (по всем попыткам внутри него); значения не округлены. */
    record RunScore(double completeness, double technical, double terminology, double speed, double coverage,
                    int words, int hedge, int answeredAttempts, Set<Integer> glossaryIds, AnswerAnalysis last) {}

    static RunScore scoreRun(InterviewKnowledge kb, QuestionRun r) {
        CompiledQuestion cq = r.cq;
        int words = 0, hedge = 0, termTokens = 0, answered = 0;
        double techW = 0, onW = 0, speedSum = 0;
        Set<Integer> gloss = new java.util.LinkedHashSet<>();
        AnswerAnalysis last = null;

        for (Attempt a : r.attempts) {
            if (!a.answered || a.analysis == null) continue;
            answered++;
            AnswerAnalysis an = a.analysis;
            last = an;
            words += an.words();
            hedge += an.hedgeCount();
            techW += an.techWeight();
            onW += an.onTopicWeight();
            termTokens += an.termTokens();
            for (int id : an.glossaryIds()) gloss.add(id);
            speedSum += speedOf(an.words(), a.timing);
        }
        if (last == null) return null;

        double R = last.coverage();
        double Rcore = last.coreCoverage();
        double lenScore = Math.min(1.0, words / (double) cq.targetWords());
        double completeness = 10 * clamp(0.75 * R + 0.25 * lenScore);

        double P = techW > 0 ? onW / techW : 0;
        double hedgePenalty = Math.min(0.4, 2.0 * hedge / Math.max(8, words));
        double technical = 10 * clamp((0.6 * Rcore + 0.25 * P + 0.15 * R) * (1 - hedgePenalty));

        double terminology = 0;
        if (words > 0) {
            double expected = Math.max(2, Math.min(6, cq.glossaryInPool()));
            double density = 100.0 * termTokens / words;
            terminology = 10 * clamp(0.55 * Math.min(1, gloss.size() / expected)
                    + 0.45 * Math.min(1, density / TARGET_TERM_DENSITY));
        }
        // скорость засчитывается пропорционально содержательности: быстрый «не знаю» — не достижение
        double speed = (speedSum / answered) * (0.3 + 0.7 * Math.min(1, R / 0.3));
        return new RunScore(completeness, technical, terminology, speed, R, words, hedge, answered, gloss, last);
    }

    static InterviewReport score(InterviewKnowledge kb, List<QuestionRun> runs, int planned, boolean earlyStop) {
        List<InterviewReport.QuestionResult> results = new ArrayList<>();
        double sumC = 0, sumT = 0, sumTerm = 0, sumS = 0;
        Set<Integer> slang = new HashSet<>();

        for (QuestionRun r : runs) {
            RunScore rs = scoreRun(kb, r);
            if (rs == null) continue;
            for (int id : rs.glossaryIds()) {
                if (kb.kindOf(id) == InterviewKnowledge.SLANG) slang.add(id);
            }
            sumC += rs.completeness();
            sumT += rs.technical();
            sumTerm += rs.terminology();
            sumS += rs.speed();
            results.add(new InterviewReport.QuestionResult(
                    results.size() + 1, r.cq.level().title, r.cq.question().topic(), r.cq.question().text(),
                    rs.answeredAttempts(), (int) Math.round(rs.coverage() * 100),
                    round2(rs.completeness()), round2(rs.technical()), round2(rs.terminology()), round2(rs.speed()),
                    missing(kb, r)));
        }

        int n = results.size();
        double c1 = n == 0 ? 0 : sumC / n;
        double c2 = n == 0 ? 0 : sumT / n;
        double meanTerm = n == 0 ? 0 : sumTerm / n;
        double c3 = Math.min(10, 0.8 * meanTerm + 0.2 * 10 * Math.min(1, slang.size() / TARGET_SLANG_DISTINCT));
        double c4 = n == 0 ? 0 : sumS / n;

        double r1 = round2(c1), r2 = round2(c2), r3 = round2(c3), r4 = round2(c4);
        double total = round2((r1 + r2 + r3 + r4) / 4.0);
        return new InterviewReport(r1, r2, r3, r4, total, String.format(java.util.Locale.ROOT, "%.2f", total),
                verdict(total), planned, n, earlyStop, results);
    }

    /** Скорость одного ответа: 60% — как быстро начал, 40% — темп речи. */
    static double speedOf(int words, AnswerTiming t) {
        if (words == 0) return 0;
        double over = t.latencyMs() - GRACE_MS;
        double lat = over <= 0 ? 10 : Math.max(0, 10 * (1 - over / LATENCY_FALLOFF_MS));
        double pace;
        if (t.speechMs() < 2000) {
            pace = 7;                                       // слишком коротко, чтобы оценить темп
        } else {
            double wpm = words / (t.speechMs() / 60000.0);
            if (wpm < 90) pace = Math.max(0, 10 * (wpm - 30) / 60);
            else if (wpm <= 180) pace = 10;
            else pace = Math.max(4, 10 - 6 * (wpm - 180) / 80);
        }
        return 0.6 * lat + 0.4 * pace;
    }

    private static List<String> missing(InterviewKnowledge kb, QuestionRun r) {
        List<String> out = new ArrayList<>();
        CompiledQuestion cq = r.cq;
        for (int p = 0; p < cq.poolSize() && out.size() < 5; p++) {
            if (!r.hits[p]) out.add(kb.label(cq.poolIds()[p]));
        }
        return out;
    }

    private static double clamp(double v) {
        return Math.max(0, Math.min(1, v));
    }

    static double round2(double v) {
        return BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }

    static String verdict(double total) {
        if (total >= 8.5) return "Отлично";
        if (total >= 7.0) return "Хорошо";
        if (total >= 5.0) return "Удовлетворительно";
        if (total >= 3.0) return "Слабо";
        return "Недостаточно";
    }
}
