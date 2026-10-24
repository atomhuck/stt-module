package ru.example.interview.engine;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Готовит текст вопроса для ОЗВУЧКИ. Русские голоса читают латиницу плохо («HashMap» → «хашмап» или по буквам),
 * поэтому английские термины заменяются русским произношением: «HashMap» → «хэшмэп», «JVM» → «джей-ви-эм».
 * На экране остаётся оригинальный текст; в динамики уходит только результат speak().
 *
 * Порядок: словарь spoken.txt (целиком) → части по «/», «_», «.», «-» → camelCase → аббревиатура по буквам → запасная транслитерация.
 * @version 1.0.0
 */
public final class SpokenText {

    private static final Pattern CHUNK =
            Pattern.compile("[A-Za-z0-9@][A-Za-z0-9_+\\-/.#@]*[A-Za-z0-9+#]|[A-Za-z]");
    private static final Pattern HAS_LATIN = Pattern.compile("[A-Za-z]");

    private static final String[] LETTERS = {"эй", "би", "си", "ди", "и", "эф", "джи", "эйч", "ай", "джей", "кей", "эл",
            "эм", "эн", "оу", "пи", "кью", "ар", "эс", "ти", "ю", "ви", "дабл-ю", "икс", "уай", "зед"};

    private final Map<String, String> dict;

    /**
     * Создаёт нормализатор произнесённого текста по словарю замен.
     *
     * @param dict словарь замен для произношения
     */
    public SpokenText(Map<String, String> dict) {
        this.dict = dict;
    }

    /**
     * Формат: «латиница=чтение», по строке; # — комментарий.
     *
     * @param text текст ответа или документа
     * @return словарь произношения технических терминов
     */
    public static SpokenText parse(String text) {
        Map<String, String> m = new HashMap<>();
        for (String line : text.split("\\R")) {
            String t = line.strip();
            if (t.isEmpty() || t.startsWith("#")) continue;
            int eq = t.indexOf('=');
            if (eq <= 0) continue;
            m.put(t.substring(0, eq).strip().toLowerCase(), t.substring(eq + 1).strip());
        }
        return new SpokenText(m);
    }

    /**
     * Подставляет произношение технических терминов для TTS.
     *
     * @param text текст ответа или документа
     * @return строка результата
     */
    public String speak(String text) {
        return process(text, null);
    }

    /**
     * Латинские фрагменты, для которых нет записи в словаре и пришлось применить запасную транслитерацию.
     *
     * @param text текст ответа или документа
     * @return список результатов
     */
    public List<String> unknown(String text) {
        List<String> out = new ArrayList<>();
        process(text, out);
        return out;
    }

    private String process(String text, List<String> unknown) {
        Matcher m = CHUNK.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String chunk = m.group();
            if (!HAS_LATIN.matcher(chunk).find()) {
                m.appendReplacement(sb, Matcher.quoteReplacement(chunk));   // просто число
                continue;
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(chunk(chunk, unknown)));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private String chunk(String chunk, List<String> unknown) {
        String hit = dict.get(chunk.toLowerCase());
        if (hit != null) return hit;
        if (chunk.indexOf('/') >= 0) {
            List<String> parts = new ArrayList<>();
            for (String p : chunk.split("/")) if (!p.isEmpty()) parts.add(piece(p, unknown));
            return String.join(" и ", parts);
        }
        return piece(chunk, unknown);
    }

    private String piece(String piece, List<String> unknown) {
        String hit = dict.get(piece.toLowerCase());
        if (hit != null) return hit;
        List<String> parts = new ArrayList<>();
        for (String p : piece.split("[_.\\-]")) {
            if (!p.isEmpty()) parts.add(word(p, unknown));
        }
        return String.join(" ", parts);
    }

    private String word(String w, List<String> unknown) {
        String hit = dict.get(w.toLowerCase());
        if (hit != null) return hit;
        // буквы + цифры («h2», «p95»): читаем отдельно
        if (w.matches("[A-Za-z]+\\d+|\\d+[A-Za-z]+")) {
            Matcher m = Pattern.compile("[A-Za-z]+|\\d+").matcher(w);
            List<String> parts = new ArrayList<>();
            while (m.find()) parts.add(Character.isDigit(m.group().charAt(0)) ? m.group() : word(m.group(), unknown));
            return String.join(" ", parts);
        }
        // camelCase: HashMap → Hash Map
        String[] camel = w.split("(?<=[a-z])(?=[A-Z])");
        if (camel.length > 1) {
            List<String> parts = new ArrayList<>();
            for (String c : camel) parts.add(word(c, unknown));
            return String.join("-", parts);
        }
        if (unknown != null) unknown.add(w);
        if (w.equals(w.toUpperCase()) && w.length() <= 5) return spell(w);
        return translit(w.toLowerCase());
    }

    /** Аббревиатура по буквам: «SQL» → «эс-кью-эл». */
    static String spell(String w) {
        List<String> out = new ArrayList<>();
        for (char c : w.toLowerCase().toCharArray()) {
            if (c >= 'a' && c <= 'z') out.add(LETTERS[c - 'a']);
        }
        return String.join("-", out);
    }

    /** Запасное чтение: грубая транслитерация по правилам английской орфографии. */
    static String translit(String w) {
        String[][] multi = {{"tion", "шн"}, {"sh", "ш"}, {"ch", "ч"}, {"th", "т"}, {"ph", "ф"}, {"ck", "к"}, {"qu", "кв"},
                {"ee", "и"}, {"oo", "у"}, {"ea", "и"}, {"ai", "эй"}, {"ay", "эй"}, {"ou", "ау"}, {"igh", "ай"}};
        String s = w;
        if (s.length() > 3 && s.endsWith("e") && "aeiouy".indexOf(s.charAt(s.length() - 2)) < 0) {
            s = s.substring(0, s.length() - 1);   // немое «e» на конце: «cache» → «cach»
        }
        StringBuilder sb = new StringBuilder();
        int i = 0;
        outer:
        while (i < s.length()) {
            for (String[] m : multi) {
                if (s.startsWith(m[0], i)) {
                    sb.append(m[1]);
                    i += m[0].length();
                    continue outer;
                }
            }
            char c = s.charAt(i);
            char next = i + 1 < s.length() ? s.charAt(i + 1) : ' ';
            switch (c) {
                case 'a' -> sb.append('э');
                case 'b' -> sb.append('б');
                case 'c' -> sb.append("eiy".indexOf(next) >= 0 ? 'с' : 'к');
                case 'd' -> sb.append('д');
                case 'e' -> sb.append(i == 0 ? 'э' : 'е');
                case 'f' -> sb.append('ф');
                case 'g' -> sb.append('г');
                case 'h' -> sb.append('х');
                case 'i' -> sb.append('и');
                case 'j' -> sb.append("дж");
                case 'k' -> sb.append('к');
                case 'l' -> sb.append('л');
                case 'm' -> sb.append('м');
                case 'n' -> sb.append('н');
                case 'o' -> sb.append('о');
                case 'p' -> sb.append('п');
                case 'q' -> sb.append('к');
                case 'r' -> sb.append('р');
                case 's' -> sb.append('с');
                case 't' -> sb.append('т');
                case 'u' -> sb.append('а');
                case 'v', 'w' -> sb.append('в');
                case 'x' -> sb.append("кс");
                case 'y' -> sb.append('и');
                case 'z' -> sb.append('з');
                default -> sb.append(c);
            }
            i++;
        }
        return sb.toString();
    }
}
