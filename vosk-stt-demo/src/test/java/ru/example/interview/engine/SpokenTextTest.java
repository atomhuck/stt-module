package ru.example.interview.engine;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SpokenTextTest {

    private final SpokenText spoken = TestKb.get().spoken();

    @Test
    void english_terms_are_read_the_russian_way() {
        assertEquals("Что такое джей-ви-эм, джей-ар-и и джей-ди-кей?", spoken.speak("Что такое JVM, JRE и JDK?"));
        assertTrue(spoken.speak("Чем HashMap отличается от HashSet?").contains("хэшмэп"));
        assertTrue(spoken.speak("Для чего нужен SQL?").contains("эс-кью-эл"));
        assertTrue(spoken.speak("Что значит N+1 в ORM?").contains("эн плюс один"));
    }

    @Test
    void plain_russian_text_is_untouched() {
        String t = "Расскажите, как работает сборщик мусора, и приведите пример на 17 версии.";
        assertEquals(t, spoken.speak(t));
    }

    @Test
    void whole_bank_is_spoken_without_any_latin_letters_and_without_fallback() {
        int texts = 0;
        for (CompiledQuestion cq : TestKb.get().questions()) {
            java.util.List<String> all = new java.util.ArrayList<>();
            all.add(cq.question().text());
            all.addAll(cq.question().followUps());
            for (String t : all) {
                texts++;
                String s = spoken.speak(t);
                assertFalse(s.matches("(?s).*[A-Za-z].*"), "осталась латиница: " + s);
                assertTrue(spoken.unknown(t).isEmpty(), "нет записи в spoken.txt для " + spoken.unknown(t) + " в «" + t + "»");
            }
        }
        assertTrue(texts > 250);
    }

    @Test
    void unknown_words_get_a_reasonable_fallback() {
        // аббревиатура — по буквам, camelCase — по частям
        assertEquals("икс-уай-зед", spoken.speak("XYZ"));
        assertFalse(spoken.speak("FooBar").matches(".*[A-Za-z].*"));
        assertFalse(spoken.unknown("XYZ").isEmpty());
    }
}
