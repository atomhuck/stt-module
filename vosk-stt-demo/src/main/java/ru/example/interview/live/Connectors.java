package ru.example.interview.live;

import java.util.Set;

/** Заканчивается ли текст союзом/предлогом: тогда человек почти наверняка продолжит, и тишину надо ждать дольше. */
final class Connectors {
    private static final Set<String> WORDS = Set.of(
            "и", "а", "но", "или", "либо", "да", "то", "что", "чтобы", "потому", "если", "когда", "например",
            "также", "как", "ну", "вот", "типа", "значит", "который", "которая", "которые", "для", "на", "в",
            "с", "со", "по", "от", "из", "к", "у", "о", "об", "при", "за", "над", "под", "без", "через",
            "между", "еще", "ещё", "там", "тут", "это", "эти", "этот", "тоже", "плюс", "то есть", "ээ", "эм");

    private Connectors() {}

    static boolean endsWithConnector(String text) {
        int end = text.length();
        while (end > 0 && !Character.isLetterOrDigit(text.charAt(end - 1))) end--;
        int start = end;
        while (start > 0 && Character.isLetterOrDigit(text.charAt(start - 1))) start--;
        if (start == end) return false;
        return WORDS.contains(text.substring(start, end).toLowerCase());
    }

    static int countWords(String text) {
        int n = 0;
        boolean in = false;
        for (int i = 0; i < text.length(); i++) {
            boolean letter = Character.isLetterOrDigit(text.charAt(i));
            if (letter && !in) n++;
            in = letter;
        }
        return n;
    }
}
