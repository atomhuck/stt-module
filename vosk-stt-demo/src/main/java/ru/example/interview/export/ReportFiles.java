package ru.example.interview.export;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.regex.Pattern;

 * @version 1.0.0
/** Имена файлов отчётов: безопасные для файловой системы и для скачивания по ссылке. */
public final class ReportFiles {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm", Locale.ROOT);
    private static final Pattern SAFE = Pattern.compile("^Interview_[\\p{L}\\p{N}_\\-]+\\.xlsx$");

    private ReportFiles() {}

    /**
     * Interview_2026-10-14_23-54_Иван_Петров.xlsx. Если файловая система сервера не умеет кириллицу
     * (Linux в POSIX-локали, часть Docker-образов), имя транслитерируется: Interview_..._Ivan_Petrov.xlsx.
     * @param candidate имя кандидата
     * @param startedAt время начала интервью
     * @return строка результата
     */
    public static String fileName(String candidate, Instant startedAt) {
        String who = candidate == null ? "" : candidate.trim().replaceAll("[^\\p{L}\\p{N}]+", "_").replaceAll("^_+|_+$", "");
        if (who.isEmpty()) who = "кандидат";
        if (who.length() > 40) who = who.substring(0, 40);
        String name = "Interview_" + STAMP.format(startedAt.atZone(ZoneId.systemDefault())) + "_" + who + ".xlsx";
        return encodable(name) ? name : translit(name);
    }

    static boolean encodable(String s) {
        String enc = System.getProperty("sun.jnu.encoding");
        try {
            return enc == null || java.nio.charset.Charset.forName(enc).newEncoder().canEncode(s);
        } catch (RuntimeException e) {
            return true;
        }
    }

    /** Русская транслитерация (близко к ГОСТ 7.79 B); прочие нелатинские символы заменяются на «_». */
    static String translit(String s) {
        String[] lat = {"a", "b", "v", "g", "d", "e", "zh", "z", "i", "y", "k", "l", "m", "n", "o", "p", "r", "s", "t", "u", "f",
                "kh", "ts", "ch", "sh", "shch", "", "y", "", "e", "yu", "ya"};
        String abc = "абвгдежзийклмнопрстуфхцчшщъыьэюя";
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            char low = Character.toLowerCase(c);
            int i = low == 'ё' ? 5 : abc.indexOf(low);
            if (i >= 0) {
                String t = lat[i];
                sb.append(Character.isUpperCase(c) && !t.isEmpty() ? Character.toUpperCase(t.charAt(0)) + t.substring(1) : t);
            } else if (c < 128) {
                sb.append(c);
            } else {
                sb.append('_');
            }
        }
        return sb.toString();
    }

     * @param name имя файла или сущности
     * @return признак выполнения условия
    /** Имя, которое мы сами могли создать (защита от обхода каталога при скачивании). */
    public static boolean isSafeName(String name) {
        return name != null && SAFE.matcher(name).matches();
    }

     * @param dir параметр операции
     * @param name имя файла или сущности
     * @return путь к файлу или null, если доступ запрещён
    /** Если файл с таким именем уже есть — добавляет _2, _3... (отчёт никогда не затирает предыдущий). */
    public static Path uniquePath(Path dir, String name) {
        Path p = dir.resolve(name);
        String base = name.substring(0, name.length() - ".xlsx".length());
        for (int i = 2; Files.exists(p); i++) p = dir.resolve(base + "_" + i + ".xlsx");
        return p;
    }
}
