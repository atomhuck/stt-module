package ru.example.interview.engine;

/**
 * Горячий путь: разбор ответа за микросекунды. Без регулярок, без создания строк,
 * рабочие массивы живут в ThreadLocal и переиспользуются.
 * @version 1.0.0
 */
public final class AnswerAnalyzer {

    private static final class Ws {
        final long[] hashes = new long[4096];
        final int[] ids = new int[4096];
        final int[] gloss = new int[256];
    }

    private static final ThreadLocal<Ws> WS = ThreadLocal.withInitial(Ws::new);

    private final InterviewKnowledge kb;

    /**
     * Создаёт объект с указанными зависимостями и настройками.
     *
     * @param kb скомпилированный банк знаний
     */
    public AnswerAnalyzer(InterviewKnowledge kb) {
        this.kb = kb;
    }

    /**
     * @param hits накопительные флаги «понятие названо» (длина = poolSize); обновляются на месте
     * @param q скомпилированный вопрос
     * @param text текст ответа или документа
     * @return результат операции
     */
    public AnswerAnalysis analyze(CompiledQuestion q, CharSequence text, boolean[] hits) {
        Ws ws = WS.get();
        int n = Tokenizer.tokenize(text, ws.hashes, null, null);
        int m = kb.mapTokens(ws.hashes, n, ws.ids);

        final byte[] kind = kb.kind;
        final double[] tw = kb.tw;
        double techW = 0, onW = 0;
        int hedge = 0, termTokens = 0, g = 0, newHits = 0;
        boolean ignorance = false;

        for (int i = 0; i < m; i++) {
            int id = ws.ids[i];
            if (id < 0) continue;
            byte k = kind[id];
            if (k == InterviewKnowledge.HEDGE) {
                hedge++;
                continue;
            }
            if (k == InterviewKnowledge.IGNORE) {
                ignorance = true;
                continue;
            }
            if (k == InterviewKnowledge.STOP) continue;
            if (k == InterviewKnowledge.TERM || k == InterviewKnowledge.SLANG) {
                termTokens++;
                boolean seen = false;
                for (int j = 0; j < g; j++) {
                    if (ws.gloss[j] == id) {
                        seen = true;
                        break;
                    }
                }
                if (!seen && g < ws.gloss.length) ws.gloss[g++] = id;
            }
            double w = tw[id];
            if (w > 0) {
                techW += w;
                if (q.topic()[id]) onW += w;
            }
            int pi = q.poolIndex()[id];
            if (pi >= 0 && !hits[pi]) {
                hits[pi] = true;
                newHits++;
            }
        }

        double got = 0, gotCore = 0;
        for (int p = 0; p < q.poolSize(); p++) {
            if (hits[p]) {
                got += q.poolW()[p];
                if (q.core()[p]) gotCore += q.poolW()[p];
            }
        }
        double cov = q.poolTotalW() > 0 ? got / q.poolTotalW() : 0;
        double coreCov = q.coreTotalW() > 0 ? gotCore / q.coreTotalW() : 0;
        int[] glossIds = new int[g];
        System.arraycopy(ws.gloss, 0, glossIds, 0, g);
        return new AnswerAnalysis(n, techW, onW, cov, coreCov, newHits, hedge, ignorance, termTokens, glossIds);
    }
}
