package ru.example.interview.engine;

import java.util.ArrayList;
import java.util.List;

/**
 * Читает questions.tsv (см. tools/xlsx_to_tsv.py).
 *
 * @version 1.0.0
 */
public final class QuestionBank {
    private QuestionBank() {}

    /**
     * Разбирает строки банка вопросов с шестью TSV-колонками.
     *
     * @param tsv содержимое TSV-банка
     * @return список результатов
     */
    public static List<Question> parse(String tsv) {
        List<Question> out = new ArrayList<>();
        int id = 1;
        for (String line : tsv.split("\\R")) {
            if (line.isBlank() || line.startsWith("#")) continue;
            String[] c = line.split("\t", -1);
            if (c.length < 6) {
                throw new IllegalArgumentException("Ожидалось 6 колонок, получено " + c.length + ": " + line);
            }
            List<String> fu = new ArrayList<>();
            for (String f : c[5].split("\\|\\|")) {
                if (!f.isBlank()) fu.add(f.trim());
            }
            out.add(new Question(id++, Level.parse(c[0]), c[1].trim(), c[2].trim(), c[3].trim(), c[4].trim(), fu));
        }
        return out;
    }
}
