package ru.example.interview.engine;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Горячий путь (разбор ответа + решение) обязан укладываться в доли миллисекунды. */
class LatencyTest {

    @Test
    void analyze_and_decide_is_far_below_a_millisecond() {
        InterviewKnowledge kb = TestKb.get();
        kb.warmUp(5);
        DecisionPolicy policy = new DecisionPolicy(2, 0.08);
        AnswerAnalyzer an = kb.analyzer();
        CompiledQuestion[] qs = kb.questions();

        int rounds = 4000;
        long best = Long.MAX_VALUE, total = 0;
        for (int i = 0; i < rounds; i++) {
            CompiledQuestion q = qs[i % qs.length];
            String text = q.question().reference() + " " + q.question().reference();   // ~100 слов
            boolean[] hits = new boolean[q.poolSize()];
            long t0 = System.nanoTime();
            AnswerAnalysis a = an.analyze(q, text, hits);
            policy.decide(q, q.level(), a, 0, 0, new boolean[q.followUps().length], hits);
            long dt = System.nanoTime() - t0;
            if (i >= 1000) {          // первые итерации — прогрев JIT
                total += dt;
                best = Math.min(best, dt);
            }
        }
        double avgMicros = total / 3000.0 / 1000.0;
        System.out.printf("analyze+decide: avg %.1f мкс, min %.1f мкс%n", avgMicros, best / 1000.0);
        assertTrue(avgMicros < 1000, "среднее время " + avgMicros + " мкс — слишком много");
    }
}
