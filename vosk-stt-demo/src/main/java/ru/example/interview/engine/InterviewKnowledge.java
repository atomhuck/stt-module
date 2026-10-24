package ru.example.interview.engine;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Всё, что можно посчитать заранее, считается здесь один раз при старте:
 * единый словарь (термины из глоссария + основы слов из банка вопросов), IDF, пулы ключевых понятий
 * эталонных ответов и привязка уточняющих вопросов к этим понятиям.
 * @version 1.0.0
 */
public final class InterviewKnowledge {

    public static final byte PLAIN = 0, TERM = 1, SLANG = 2, HEDGE = 3, IGNORE = 4, STOP = 5;
    private static final int MAX_PHRASE = 5;
    private static final int POOL_MAX = 18;

    private final LongIntMap phrases = new LongIntMap(2048);
    private final LongIntMap stems = new LongIntMap(4096);
    private int maxPhraseLen = 1;

    String[] label;
    byte[] kind;
    boolean[] tech;
    double[] idf;
    /** «Техническая» масса токена: IDF × коэффициент вида слова; 0 — слово не техническое. */
    double[] tw;
    int vocabSize;

    private CompiledQuestion[] compiled;
    private final Map<Integer, CompiledQuestion> byId = new HashMap<>();
    private AnswerAnalyzer analyzer;
    private TermNormalizer normalizer;
    private SpokenText spoken = new SpokenText(new HashMap<>());

    private InterviewKnowledge() {}

    // ------------------------------------------------------------------ сборка

    /**
     * Компилирует банк вопросов и словарь для быстрого анализа ответов.
     *
     * @param questions вопросы банка
     * @param glossary словарь терминов
     * @return подготовленный банк знаний
     */
    public static InterviewKnowledge build(List<Question> questions, Glossary glossary) {
        InterviewKnowledge kb = new InterviewKnowledge();
        List<String> labels = new ArrayList<>();
        List<Byte> kinds = new ArrayList<>();
        long[] h = new long[64];

        // 1. Глоссарий: фразы → id понятия
        for (Glossary.Entry e : glossary.entries()) {
            int id = labels.size();
            labels.add(e.name());
            kinds.add(switch (e.kind()) {
                case TERM -> TERM;
                case SLANG -> SLANG;
                case HEDGE -> HEDGE;
                case IGNORE -> IGNORE;
            });
            Set<String> variants = new HashSet<>();
            variants.add(e.name().toLowerCase());
            variants.addAll(e.variants());
            for (String v : variants) {
                int n = Tokenizer.tokenize(v, h, null, null);
                if (n == 0 || n > MAX_PHRASE) continue;
                kb.phrases.put(Tokenizer.ngramKey(h, 0, n), id);
                kb.maxPhraseLen = Math.max(kb.maxPhraseLen, n);
            }
        }

        // 2. Словарь основ + id-последовательности текстов
        int N = questions.size();
        int[][] qIds = new int[N][];
        int[][] refIds = new int[N][];
        int[][][] fuIds = new int[N][][];
        for (int i = 0; i < N; i++) {
            Question q = questions.get(i);
            qIds[i] = kb.idsOf(q.text(), true, labels, kinds);
            refIds[i] = kb.idsOf(q.reference(), true, labels, kinds);
            fuIds[i] = new int[q.followUps().size()][];
            for (int j = 0; j < fuIds[i].length; j++) {
                fuIds[i][j] = kb.idsOf(q.followUps().get(j), true, labels, kinds);
            }
        }

        int V = labels.size();
        kb.vocabSize = V;
        kb.label = labels.toArray(new String[0]);
        kb.kind = new byte[V];
        for (int i = 0; i < V; i++) kb.kind[i] = kinds.get(i);

        // 3. Документная частота → IDF
        int[] df = new int[V];
        for (int i = 0; i < N; i++) {
            Set<Integer> seen = new HashSet<>();
            addAll(seen, qIds[i]);
            addAll(seen, refIds[i]);
            for (int[] f : fuIds[i]) addAll(seen, f);
            for (int id : seen) df[id]++;
        }
        kb.idf = new double[V];
        kb.tech = new boolean[V];
        kb.tw = new double[V];
        int dfMax = Math.max(4, (int) Math.round(0.10 * N));
        for (int id = 0; id < V; id++) {
            kb.idf[id] = Math.log(1.0 + (double) N / Math.max(1, df[id]));
            byte k = kb.kind[id];
            if (k == TERM || k == SLANG) {
                kb.tech[id] = true;
            } else if (k == PLAIN) {
                boolean cyr = isCyr(kb.label[id].charAt(0));
                // русские «служебные по смыслу» слова из прозы эталонов (поставках, распространяется...) не считаем ключевыми
                kb.tech[id] = df[id] <= dfMax && kb.label[id].length() >= (cyr ? 5 : 3);
            }
            if (kb.tech[id]) {
                // термины из глоссария — самые весомые; прочие слова эталонов — фон (0.5)
                // слишком «общие» термины (Java, SQL — есть в каждом пятом вопросе) весят меньше
                double f = (k == TERM || k == SLANG) ? (df[id] > 0.2 * N ? 0.8 : 1.6) : 0.5;
                kb.tw[id] = kb.idf[id] * f;
            }
        }

        // 4. Пулы понятий
        kb.compiled = new CompiledQuestion[N];
        for (int i = 0; i < N; i++) {
            CompiledQuestion cq = kb.compile(questions.get(i), qIds[i], refIds[i], fuIds[i]);
            kb.compiled[i] = cq;
            kb.byId.put(cq.id(), cq);
        }
        kb.analyzer = new AnswerAnalyzer(kb);
        kb.normalizer = new TermNormalizer(glossary);
        return kb;
    }

    private static void addAll(Set<Integer> set, int[] ids) {
        for (int id : ids) if (id >= 0) set.add(id);
    }

    /** Версия разбора для сборки: присваивает id новым основам. */
    private int[] idsOf(String text, boolean assign, List<String> labels, List<Byte> kinds) {
        long[] hs = new long[4096];
        String[] st = new String[4096];
        String[] sf = new String[4096];
        int n = Tokenizer.tokenize(text, hs, st, sf);
        int[] out = new int[n];
        int m = 0;
        int i = 0;
        while (i < n) {
            int matched = 0;
            int id = -1;
            for (int len = Math.min(maxPhraseLen, n - i); len >= 1; len--) {
                id = phrases.get(Tokenizer.ngramKey(hs, i, len), -1);
                if (id >= 0) {
                    matched = len;
                    break;
                }
            }
            if (matched > 0) {
                out[m++] = id;
                i += matched;
                continue;
            }
            id = stems.get(hs[i], -1);
            if (id < 0 && assign) {
                id = labels.size();
                labels.add(sf[i]);
                kinds.add(Tokenizer.isStop(st[i]) ? STOP : PLAIN);
                stems.put(hs[i], id);
            }
            out[m++] = id;
            i++;
        }
        return Arrays.copyOf(out, m);
    }

    private static boolean isCyr(char c) {
        c = Character.toLowerCase(c);
        return c >= 'а' && c <= 'я' || c == 'ё';
    }

    private double weight(int id, int tf) {
        return tw[id] * (1 + 0.15 * Math.min(3, tf - 1));
    }

    private CompiledQuestion compile(Question q, int[] qText, int[] ref, int[][] fu) {
        Set<Integer> exclude = new HashSet<>();
        addAll(exclude, qText);

        Map<Integer, Integer> tf = new LinkedHashMap<>();
        Map<Integer, Integer> tfExcluded = new LinkedHashMap<>();
        for (int id : ref) {
            if (id < 0 || !tech[id]) continue;
            (exclude.contains(id) ? tfExcluded : tf).merge(id, 1, Integer::sum);
        }
        if (tf.size() < 5) { // вопрос почти целиком состоит из терминов эталона — возвращаем их в пул
            tfExcluded.forEach((k, v) -> tf.merge(k, v, Integer::sum));
        }
        List<int[]> ranked = new ArrayList<>();
        for (Map.Entry<Integer, Integer> e : tf.entrySet()) {
            ranked.add(new int[]{e.getKey(), e.getValue()});
        }
        ranked.sort((a, b) -> {
            int c = Double.compare(weight(b[0], b[1]), weight(a[0], a[1]));
            return c != 0 ? c : Integer.compare(a[0], b[0]);
        });
        int size = Math.min(POOL_MAX, ranked.size());
        int[] poolIds = new int[size];
        double[] poolW = new double[size];
        boolean[] core = new boolean[size];
        double total = 0, coreTotal = 0;
        int coreN = Math.max(1, (int) Math.ceil(size * 0.4));
        int glossIn = 0;
        int[] poolIndex = new int[vocabSize];
        Arrays.fill(poolIndex, -1);
        for (int p = 0; p < size; p++) {
            int id = ranked.get(p)[0];
            poolIds[p] = id;
            poolW[p] = weight(id, ranked.get(p)[1]);
            core[p] = p < coreN;
            total += poolW[p];
            if (core[p]) coreTotal += poolW[p];
            poolIndex[id] = p;
            if (kind[id] == TERM || kind[id] == SLANG) glossIn++;
        }

        boolean[] topic = new boolean[vocabSize];
        for (int id : ref) if (id >= 0 && tech[id]) topic[id] = true;
        for (int id : qText) if (id >= 0 && tech[id]) topic[id] = true;
        CompiledQuestion.FollowUp[] fus = new CompiledQuestion.FollowUp[fu.length];
        for (int j = 0; j < fu.length; j++) {
            Set<Integer> idx = new HashSet<>();
            for (int id : fu[j]) {
                if (id >= 0 && tech[id]) topic[id] = true;
                if (id >= 0 && poolIndex[id] >= 0) idx.add(poolIndex[id]);
            }
            fus[j] = new CompiledQuestion.FollowUp(q.followUps().get(j),
                    idx.stream().mapToInt(Integer::intValue).sorted().toArray());
        }

        long[] hs = new long[4096];
        int refWords = Tokenizer.tokenize(q.reference(), hs, null, null);
        int target = (int) Math.max(30, Math.min(110, Math.round(1.3 * refWords)));
        return new CompiledQuestion(q, poolIds, poolW, core, total, coreTotal, poolIndex, topic, fus,
                refWords, target, glossIn);
    }

    // ------------------------------------------------------------------ горячий путь

    /** Хеши токенов → id словаря (−1 — слово неизвестно). Самые длинные фразы глоссария побеждают. */
    int mapTokens(long[] hs, int n, int[] out) {
        int m = 0;
        int i = 0;
        while (i < n) {
            int matched = 0;
            int id = -1;
            for (int len = Math.min(maxPhraseLen, n - i); len >= 1; len--) {
                id = phrases.get(Tokenizer.ngramKey(hs, i, len), -1);
                if (id >= 0) {
                    matched = len;
                    break;
                }
            }
            if (matched > 0) {
                out[m++] = id;
                i += matched;
            } else {
                out[m++] = stems.get(hs[i], -1);
                i++;
            }
        }
        return m;
    }

    // ------------------------------------------------------------------ доступ

    /**
     * Возвращает скомпилированные вопросы.
     *
     * @return скомпилированные вопросы
     */
    public CompiledQuestion[] questions() {
        return compiled;
    }

    /**
     * Находит вопрос по идентификатору.
     *
     * @param id идентификатор вопроса
     * @return скомпилированный вопрос
     */
    public CompiledQuestion byId(int id) {
        return byId.get(id);
    }

    /**
     * Возвращает анализатор ответов для этого банка.
     *
     * @return анализатор ответов
     */
    public AnswerAnalyzer analyzer() {
        return analyzer;
    }

    /**
     * Исправляет «услышанные» Vosk английские термины (хешмеп → HashMap).
     *
     * @return значение normalizer
     */
    public TermNormalizer normalizer() {
        return normalizer;
    }

    /**
     * Русское чтение английских терминов для озвучки.
     *
     * @return значение spoken
     */
    public SpokenText spoken() {
        return spoken;
    }

    /**
     * Присоединяет словарь произношения вопросов.
     *
     * @param spoken словарь произношения
     * @return подготовленный банк знаний
     */
    public InterviewKnowledge withSpoken(SpokenText spoken) {
        this.spoken = spoken;
        return this;
    }

    /**
     * Возвращает текст словарной единицы.
     *
     * @param vocabId идентификатор термина в словаре
     * @return строка результата
     */
    public String label(int vocabId) {
        return label[vocabId];
    }

    /**
     * Возвращает тип словарной единицы.
     *
     * @param vocabId идентификатор термина в словаре
     * @return вид термина в словаре
     */
    public byte kindOf(int vocabId) {
        return kind[vocabId];
    }

    /**
     * Возвращает размер словаря.
     *
     * @return целочисленное значение
     */
    public int vocabSize() {
        return vocabSize;
    }

    /**
     * Прогрев JIT: прогоняем несколько «интервью», где ответом служит сам эталон.
     * Без прогрева первые вызовы в холодной JVM заметно медленнее.
     * @param rounds число прогревочных итераций
     */
    public void warmUp(int rounds) {
        DecisionPolicy policy = new DecisionPolicy(2, 0.08);
        AnswerTiming timing = new AnswerTiming(1200, 20000);
        for (int r = 0; r < rounds; r++) {
            InterviewSession s = new InterviewSession(this, null, policy);
            InterviewSession.Prompt p = s.start();
            while (p != null) {
                CompiledQuestion cq = byId(p.questionId());
                String answer = p.followUp() ? cq.question().followUps().get(0) : cq.question().reference();
                InterviewSession.Step step = s.submit(answer, timing);
                p = step.next();
            }
            s.report();
        }
    }
}
