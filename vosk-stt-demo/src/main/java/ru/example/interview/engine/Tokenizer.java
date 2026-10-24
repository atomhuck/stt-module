package ru.example.interview.engine;

import java.util.HashSet;
import java.util.Set;

/**
 * Быстрый токенизатор + «лёгкий» стеммер для русского и английского.
 * Один проход по символам, без регулярных выражений; результат — 64-битные хеши основ (без создания строк).
 * Одна и та же функция используется и при сборке словаря, и при разборе ответа, поэтому формы слов совпадают.
 * @version 1.0.0
 */
public final class Tokenizer {

    public static final int MAX_STEM = 6;

    private static final String[] SUF3 = {"ами", "ями", "ого", "его", "ому", "ему", "ыми", "ими"};
    private static final String[] SUF2 = {"ов", "ев", "ом", "ем", "ам", "ям", "ах", "ях", "ой", "ей", "ую", "юю",
            "ая", "яя", "ое", "ее", "ые", "ие", "ый", "ий", "ью"};
    private static final String SUF1 = "аяоеыиуюьй";

    private static final Set<String> STOP = new HashSet<>();

    static {
        String words = "и в во не что он на я с со как а то все она так его но да ты к у же вы за бы по только ее мне было "
                + "вот от меня еще нет о из ему теперь когда даже ну вдруг ли если уже или ни быть был него до вас нибудь "
                + "опять уж вам ведь там потом себя ничего ей может они тут где есть надо ней для мы тебя их чем была сам "
                + "чтоб без будто чего раз тоже себе под будет ж тогда кто этот того потому этого какой совсем ним здесь "
                + "этом один почти мой тем чтобы нее сейчас были куда зачем всех никогда можно при наконец два об другой "
                + "хоть после над больше тот через эти нас про всего них какая много разве три эту моя впрочем хорошо "
                + "свою этой перед иногда лучше чуть том нельзя такой им более всегда конечно всю между это также "
                + "который которые которая которое которых например либо или тоже просто очень такое такие такая "
                + "нужно нужны необходимо позволяет позволяют используется используют использовать может могут "
                + "является являются должен должна должны должно можно чтобы если иначе либо также именно "
                + "the a an of to in is are and or for with on as by it that this be";
        for (String w : words.split(" ")) {
            char[] c = w.toCharArray();
            int len = stem(c, c.length);
            STOP.add(new String(c, 0, len));
        }
    }

    private Tokenizer() {}

    /**
     * Определяет, исключается ли основа слова из анализа ответа.
     *
     * @param stem основа слова
     * @return признак выполнения условия
     */
    public static boolean isStop(String stem) {
        return STOP.contains(stem);
    }

    /**
     * Разбирает текст на слова.
     *
     * @param hashes   выход: хеши основ
     * @param stems    выход (может быть null): строки основ
     * @param surfaces выход (может быть null): исходные слова в нижнем регистре
     * @param text текст ответа или документа
     * @return число токенов
     */
    public static int tokenize(CharSequence text, long[] hashes, String[] stems, String[] surfaces) {
        final int max = hashes.length;
        final int total = text.length();
        char[] w = new char[48];
        int len = 0;
        int n = 0;
        for (int i = 0; i <= total; i++) {
            char c = i < total ? text.charAt(i) : ' ';
            if (Character.isLetterOrDigit(c)) {
                if (len < w.length) w[len++] = fold(c);
            } else if (len > 0) {
                if (n < max) {
                    int raw = len;
                    int sl = stem(w, len);
                    hashes[n] = hash(w, sl);
                    if (stems != null) stems[n] = new String(w, 0, sl);
                    if (surfaces != null) surfaces[n] = new String(w, 0, raw);
                    n++;
                }
                len = 0;
            }
        }
        return n;
    }

    private static char fold(char c) {
        c = Character.toLowerCase(c);
        return c == 'ё' ? 'е' : c;
    }

    private static boolean isCyr(char c) {
        return c >= 'а' && c <= 'я';
    }

    static int stem(char[] w, int len) {
        if (len == 0) return 0;
        if (!isCyr(w[0])) {
            // латиница/цифры: убираем множественное «s» (beans → bean, но class остаётся)
            if (len > 4 && w[len - 1] == 's' && w[len - 2] != 's') return len - 1;
            return len;
        }
        // «типа» — частое слово-паразит; не даём ему слиться с «тип/типы»
        if (len == 4 && w[0] == 'т' && w[1] == 'и' && w[2] == 'п' && w[3] == 'а') return 4;

        int out = len;
        boolean cut = false;
        if (len - 3 >= 3) {
            for (String s : SUF3) {
                if (endsWith(w, len, s)) {
                    out = len - 3;
                    cut = true;
                    break;
                }
            }
        }
        if (!cut && len - 2 >= 3) {
            for (String s : SUF2) {
                if (endsWith(w, len, s)) {
                    out = len - 2;
                    cut = true;
                    break;
                }
            }
        }
        if (!cut && len - 1 >= 3 && SUF1.indexOf(w[len - 1]) >= 0) {
            out = len - 1;
        }
        return Math.min(out, MAX_STEM);
    }

    private static boolean endsWith(char[] w, int len, String s) {
        int sl = s.length();
        for (int i = 0; i < sl; i++) {
            if (w[len - sl + i] != s.charAt(i)) return false;
        }
        return true;
    }

    private static long hash(char[] w, int len) {
        long h = 0xcbf29ce484222325L;
        for (int i = 0; i < len; i++) {
            h ^= w[i];
            h *= 0x100000001b3L;
        }
        return h;
    }

    /**
     * Ключ n-граммы из хешей токенов (для поиска фраз вида «сборщик мусора»).
     *
     * @param h хеш массива токенов
     * @param from начальная позиция
     * @param len число значимых байтов
     * @return значение в миллисекундах или счётчик
     */
    public static long ngramKey(long[] h, int from, int len) {
        if (len == 1) return h[from];
        long k = 0x9E3779B97F4A7C15L ^ len;
        for (int j = 0; j < len; j++) {
            k = (k ^ h[from + j]) * 0x100000001b3L;
            k = Long.rotateLeft(k, 27);
        }
        return k;
    }
}
