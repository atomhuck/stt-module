package ru.example.interview.engine;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class TokenizerTest {

    private static long h(String word) {
        long[] out = new long[4];
        assertEquals(1, Tokenizer.tokenize(word, out, null, null));
        return out[0];
    }

    @Test
    void russian_word_forms_share_one_stem() {
        long base = h("поток");
        assertEquals(base, h("потока"));
        assertEquals(base, h("потоки"));
        assertEquals(base, h("потоков"));
        assertEquals(base, h("потокам"));
        assertEquals(h("класс"), h("классы"));
        assertEquals(h("класс"), h("классов"));
        assertEquals(h("синхронизация"), h("синхронизации"));
    }

    @Test
    void filler_word_tipa_is_not_merged_with_type() {
        assertNotEquals(h("тип"), h("типа"));
    }

    @Test
    void yo_is_folded_and_case_ignored() {
        assertEquals(h("объём"), h("ОБЪЕМ"));
        assertEquals(h("bean"), h("Beans"));
    }

    @Test
    void splits_on_punctuation_and_hyphens() {
        long[] out = new long[16];
        assertEquals(4, Tokenizer.tokenize("хэш-мап, это: быстро!", out, null, null));
    }
}
