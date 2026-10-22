package ru.example.interview.engine;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Исправляет то, как Vosk (русская модель) «слышит» английские термины.
 *
 * Идея: сравниваем не написание, а «звуковой скелет» слова — согласные в общем алфавите, гласные отброшены:
 * «хэш мап», «хешмеп», «hashmap» → hSmp; «спринг бут», «спринг бутт» → sprngbt. Поэтому не нужно перечислять
 * все варианты написания в словаре: достаточно канонического и пары образцов.
 *
 * Два режима:
 *  • анализ  — все найденные термины заменяются каноническим именем (чтобы анализатор их засчитал);
 *  • показ   — только «собственные имена» (HashMap, JVM, Spring Boot: в имени есть заглавная латинская буква),
 *              чтобы на экране было «HashMap», но обычные русские слова («куча», «поток») не переписывались.
 * @version 1.0.0
 */
public final class TermNormalizer {

    private static final int MIN_SINGLE = 5;     // скелет одного слова: ≥5 согласных (короче — слишком много случайных совпадений)
    private static final int MIN_MULTI = 5;      // склейка нескольких слов
    private static final int MIN_PROPER = 4;    // для «собственных имён» (HashMap, Hibernate...) хватает 4 согласных
    private static final int MIN_SPELLED = 3;    // «эс кью эль»: все слова короткие (≤4 букв) — читаем по буквам
    private static final int MAX_WINDOW = 4;

    /** Служебные слова: в склеиваемое окно они не попадают («джава это» ≠ «JWT»). */
    private static final java.util.Set<String> FUNCTION_WORDS = new java.util.HashSet<>(java.util.Arrays.asList(
            "и", "в", "во", "не", "на", "я", "с", "со", "как", "а", "то", "но", "да", "к", "у", "же", "за", "бы", "по", "от",
            "из", "о", "об", "до", "для", "при", "про", "над", "под", "без", "через", "между", "это", "этот", "эта", "эти",
            "тот", "так", "там", "тут", "вот", "ну", "что", "чтобы", "если", "или", "либо", "тоже", "также", "еще", "все",
            "он", "она", "оно", "они", "мы", "вы", "ты", "его", "ее", "их", "мне", "нет", "есть", "был", "была", "было",
            "были", "будет", "быть", "ли", "уже", "ещё", "всё", "её"));

    private final boolean phonetic;
    private final Map<String, Integer> exact = new HashMap<>();
    private final Map<String, Integer> bySkeleton = new HashMap<>();
    private final List<String> names = new ArrayList<>();
    private final List<Boolean> displayRewrite = new ArrayList<>();
    /** Звуковые скелеты латинских написаний термина: на экран переписываем только то, что звучит как английское имя. */
    private final List<java.util.Set<String>> latinSkeletons = new ArrayList<>();

    /**
     * Создаёт объект с указанными зависимостями и настройками.
     *
     * @param glossary словарь терминов
     */
    public TermNormalizer(Glossary glossary) {
        this(glossary, true);
    }

     * @param glossary словарь терминов
    /** @param phonetic false — только точные варианты из словаря (без сопоставления «по звучанию») */
    public TermNormalizer(Glossary glossary, boolean phonetic) {
        this.phonetic = phonetic;
        for (Glossary.Entry e : glossary.entries()) {
            if (e.kind() != Glossary.Kind.TERM && e.kind() != Glossary.Kind.SLANG) continue;
            int idx = names.size();
            names.add(e.name());
            boolean proper = hasUpperLatin(e.name());
            displayRewrite.add(proper);
            java.util.Set<String> latin = new java.util.HashSet<>();
            latinSkeletons.add(latin);
            List<String> variants = new ArrayList<>(e.variants());
            variants.add(e.name().toLowerCase());
            for (String v : variants) {
                String ek = exactKey(v);
                if (!ek.isEmpty()) {
                    Integer o = exact.get(ek);
                    if (o == null) exact.put(ek, idx);
                    else if (o != idx) exact.put(ek, -2);
                }
                String sk = skeleton(v);
                if (hasLatin(v)) latin.add(sk);
                int words = countWords(v);
                boolean spelled = words > 1 && allShort(v);
                int min = spelled ? MIN_SPELLED : (proper ? MIN_PROPER : (words > 1 ? MIN_MULTI : MIN_SINGLE));
                if (sk.length() < min) continue;
                // «по звучанию» сопоставляем только то, что звучит как ЛАТИНСКОЕ имя термина (hashmap, spring boot, jvm...).
                // Родные русские слова («список», «запуск», «блокировка») ищутся только точно — иначе «запуск» ≈ «список».
                if (!hasLatin(v)) continue;
                Integer old = bySkeleton.get(sk);
                if (old == null) bySkeleton.put(sk, idx);
                else if (old != idx) bySkeleton.put(sk, -2);      // двусмысленный скелет — не используем
            }
        }
    }

    /**
     * Нормализует технические термины в распознанном ответе.
     *
     * @param text текст ответа или документа
     * @param display текст для показа пользователю
     * @return строка результата
     */
    public String normalize(String text, boolean display) {
        if (text == null || text.isEmpty()) return text;
        // токены: слова из букв/цифр с позициями
        List<int[]> tok = new ArrayList<>();
        int n = text.length();
        int i = 0;
        while (i < n) {
            if (Character.isLetterOrDigit(text.charAt(i))) {
                int s = i;
                while (i < n && Character.isLetterOrDigit(text.charAt(i))) i++;
                tok.add(new int[]{s, i});
            } else {
                i++;
            }
        }
        int t = tok.size();
        if (t == 0) return text;
        String[] sk = new String[t];
        for (int k = 0; k < t; k++) sk[k] = skeleton(text.substring(tok.get(k)[0], tok.get(k)[1]));

        StringBuilder out = new StringBuilder(n + 16);
        int copied = 0;
        int k = 0;
        while (k < t) {
            boolean replaced = false;
            for (int len = Math.min(MAX_WINDOW, t - k); len >= 1 && !replaced; len--) {
                // 1) точный вариант из словаря (любая длина)
                StringBuilder ek = new StringBuilder();
                boolean hasFunction = false;
                boolean allShort = true;
                for (int j = k; j < k + len; j++) {
                    String w = text.substring(tok.get(j)[0], tok.get(j)[1]).toLowerCase().replace('ё', 'е');
                    if (j > k) ek.append(' ');
                    ek.append(w);
                    if (FUNCTION_WORDS.contains(w)) hasFunction = true;
                    if (w.length() > 4) allShort = false;
                }
                StringBuilder key = new StringBuilder();
                for (int j = k; j < k + len; j++) key.append(sk[j]);
                String windowSk = key.toString();
                Integer idx = exact.get(ek.toString());
                // 2) «по звучанию»: только для окон без служебных слов
                if ((idx == null || idx < 0) && phonetic && !(len > 1 && hasFunction)) {
                    Integer cand = bySkeleton.get(windowSk);
                    if (cand != null && cand >= 0) {
                        int min = allShort && len > 1 ? MIN_SPELLED : (displayRewrite.get(cand) ? MIN_PROPER : (len == 1 ? MIN_SINGLE : MIN_MULTI));
                        if (windowSk.length() >= min) idx = cand;
                    }
                }
                if (idx == null || idx < 0) continue;
                // на экране — только «собственные имена», и только если окно ЗВУЧИТ как английское написание
                if (display && !(displayRewrite.get(idx) && latinSkeletons.get(idx).contains(windowSk))) continue;
                int start = tok.get(k)[0];
                int end = tok.get(k + len - 1)[1];
                out.append(text, copied, start).append(names.get(idx));
                copied = end;
                k += len;
                replaced = true;
            }
            if (!replaced) k++;
        }
        out.append(text, copied, n);
        return out.toString();
    }

    // ------------------------------------------------------------------ звуковой скелет

    /** Согласные в общем (русско-английском) алфавите; гласные и ь/ъ/й отброшены; повторы схлопнуты. */
    static String skeleton(CharSequence s) {
        StringBuilder sb = new StringBuilder();
        int n = s.length();
        for (int i = 0; i < n; i++) {
            char c = Character.toLowerCase(s.charAt(i));
            char next = i + 1 < n ? Character.toLowerCase(s.charAt(i + 1)) : ' ';
            if (c >= 'a' && c <= 'z') {
                switch (c) {
                    case 't' -> {
                        if (n - i >= 4 && "tion".contentEquals(s.subSequence(i, i + 4).toString().toLowerCase())) {
                            put(sb, 'S');
                            put(sb, 'n');
                            i += 3;
                        } else if (next == 'h') {
                            put(sb, 't');
                            i++;
                        } else {
                            put(sb, 't');
                        }
                    }
                    case 's' -> {
                        if (next == 'h') {
                            put(sb, 'S');
                            i++;
                        } else {
                            put(sb, 's');
                        }
                    }
                    case 'c' -> {
                        if (next == 'h') {
                            put(sb, 'C');
                            i++;
                        } else if (next == 'k') {
                            put(sb, 'k');
                            i++;
                        } else {
                            put(sb, "eiy".indexOf(next) >= 0 ? 's' : 'k');
                        }
                    }
                    case 'p' -> {
                        if (next == 'h') {
                            put(sb, 'f');
                            i++;
                        } else {
                            put(sb, 'p');
                        }
                    }
                    case 'k' -> {
                        if (next == 'h') {
                            put(sb, 'h');
                            i++;
                        } else {
                            put(sb, 'k');
                        }
                    }
                    case 'q' -> {
                        put(sb, 'k');
                        if (next == 'u') i++;
                    }
                    case 'j' -> {
                        put(sb, 'd');
                        put(sb, 'j');
                    }
                    case 'x' -> {
                        put(sb, 'k');
                        put(sb, 's');
                    }
                    case 'z' -> put(sb, 's');
                    case 'w', 'v' -> put(sb, 'v');
                    case 'b', 'd', 'f', 'g', 'h', 'l', 'm', 'n', 'r' -> put(sb, c);
                    default -> { /* a e i o u y — гласные */ }
                }
            } else if (c >= 'а' && c <= 'я' || c == 'ё') {
                switch (c) {
                    case 'б' -> put(sb, 'b');
                    case 'в' -> put(sb, 'v');
                    case 'г' -> put(sb, 'g');
                    case 'д' -> put(sb, 'd');
                    case 'ж' -> put(sb, 'j');
                    case 'з', 'с' -> put(sb, 's');
                    case 'к' -> put(sb, 'k');
                    case 'л' -> put(sb, 'l');
                    case 'м' -> put(sb, 'm');
                    case 'н' -> put(sb, 'n');
                    case 'п' -> put(sb, 'p');
                    case 'р' -> put(sb, 'r');
                    case 'т' -> put(sb, 't');
                    case 'ф' -> put(sb, 'f');
                    case 'х' -> put(sb, 'h');
                    case 'ц' -> {
                        put(sb, 't');
                        put(sb, 's');
                    }
                    case 'ч' -> put(sb, 'C');
                    case 'ш', 'щ' -> put(sb, 'S');
                    default -> { /* гласные, й, ь, ъ */ }
                }
            } else if (Character.isDigit(c)) {
                put(sb, c);
            }
        }
        return sb.toString();
    }

    private static void put(StringBuilder sb, char c) {
        int len = sb.length();
        if (len > 0 && sb.charAt(len - 1) == c) return;   // схлопываем удвоенные
        sb.append(c);
    }

    private static String exactKey(String v) {
        StringBuilder sb = new StringBuilder();
        int n = v.length();
        int i = 0;
        while (i < n) {
            if (Character.isLetterOrDigit(v.charAt(i))) {
                int st = i;
                while (i < n && Character.isLetterOrDigit(v.charAt(i))) i++;
                if (sb.length() > 0) sb.append(' ');
                sb.append(v.substring(st, i).toLowerCase().replace('ё', 'е'));
            } else {
                i++;
            }
        }
        return sb.toString();
    }

    private static boolean allShort(String v) {
        int len = 0;
        for (int i = 0; i <= v.length(); i++) {
            boolean l = i < v.length() && Character.isLetterOrDigit(v.charAt(i));
            if (l) len++;
            else {
                if (len > 4) return false;
                len = 0;
            }
        }
        return true;
    }

    private static boolean hasLatin(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = Character.toLowerCase(s.charAt(i));
            if (c >= 'a' && c <= 'z') return true;
        }
        return false;
    }

    private static boolean hasUpperLatin(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 'A' && c <= 'Z') return true;
        }
        return false;
    }

    private static int countWords(String s) {
        int n = 0;
        boolean in = false;
        for (int i = 0; i < s.length(); i++) {
            boolean l = Character.isLetterOrDigit(s.charAt(i));
            if (l && !in) n++;
            in = l;
        }
        return n;
    }
}
