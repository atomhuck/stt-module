package ru.example.interview.engine;

import java.util.ArrayList;
import java.util.List;

/**
 * Словарь профессиональных терминов и сленга. Формат строки:  вид;каноническое имя;вариант1|вариант2|...
 * Виды: term, slang, hedge (слова-паразиты/неуверенность), ignore («не знаю»).
 * В вариантах указываем и латиницу, и русскую транскрипцию: Vosk выдаёт «хэш мап», а не «HashMap».
 * @version 1.0.0
 */
public final class Glossary {

    /**
     * Различает термины, сленг, слова неуверенности и игнорируемые выражения.
     *
     * @version 1.0.0
     */
    public enum Kind { TERM, SLANG, HEDGE, IGNORE }

    /**
     * Связывает канонический термин с его речевыми вариантами.
     *
     * @version 1.0.0
     */
    public record Entry(String name, Kind kind, List<String> variants) {}

    private final List<Entry> entries;

    private Glossary(List<Entry> entries) {
        this.entries = entries;
    }

    /**
     * Возвращает записи глоссария.
     *
     * @return список результатов
     */
    public List<Entry> entries() {
        return entries;
    }

    /**
     * Разбирает словарь речевых вариантов и видов терминов.
     *
     * @param text текст ответа или документа
     * @return значение parse
     */
    public static Glossary parse(String text) {
        List<Entry> out = new ArrayList<>();
        for (String line : text.split("\\R")) {
            String t = line.strip();
            if (t.isEmpty() || t.startsWith("#")) continue;
            String[] p = t.split(";", 3);
            if (p.length < 3) throw new IllegalArgumentException("Некорректная строка словаря: " + line);
            Kind kind = Kind.valueOf(p[0].strip().toUpperCase());
            List<String> variants = new ArrayList<>();
            for (String v : p[2].split("\\|")) {
                if (!v.isBlank()) variants.add(v.strip().toLowerCase());
            }
            out.add(new Entry(p[1].strip(), kind, variants));
        }
        return new Glossary(out);
    }
}
