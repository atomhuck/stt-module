package ru.example.interview.engine;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TermNormalizerTest {

    private final TermNormalizer n = TestKb.get().normalizer();

    @Test
    void misheard_english_terms_are_restored_for_display() {
        assertEquals("это HashMap который хранит пары", n.normalize("это хешмеп который хранит пары", true));
        assertEquals("я использую Spring Boot и Hibernate", n.normalize("я использую спринг бутт и хибернэйт", true));
        assertEquals("JVM исполняет байткод", n.normalize("джи ви эм исполняет байткод", true));
        assertEquals("Kubernetes и Docker", n.normalize("кубернетис и докер", true));
        assertEquals("Kafka и RabbitMQ", n.normalize("кафка и рэбит эм кью", true));
        assertEquals("Java это язык", n.normalize("джава это язык", true));
    }

    @Test
    void ordinary_russian_words_are_not_rewritten_on_screen() {
        String t = "своей транзакционной модели берёт блокировку и среду выполнения для запуска";
        assertEquals(t, n.normalize(t, true));
    }

    @Test
    void function_words_do_not_glue_into_a_term() {
        // «джава это» не должно склеиться в «JWT»
        assertFalse(n.normalize("джава это язык", false).contains("JWT"));
    }

    @Test
    void analysis_mode_makes_misheard_terms_countable() {
        InterviewKnowledge kb = TestKb.get();
        CompiledQuestion q = kb.byId(1);
        String raw = "джи ви эм это виртуальная машина которая исполняет байткод а джи ди кей это комплект разработчика";
        AnswerAnalysis plain = kb.analyzer().analyze(q, raw, new boolean[q.poolSize()]);
        AnswerAnalysis norm = kb.analyzer().analyze(q, n.normalize(raw, false), new boolean[q.poolSize()]);
        assertTrue(norm.termTokens() >= plain.termTokens());
    }

    @Test
    void russian_prose_of_the_bank_has_no_false_phonetic_rewrites() {
        TermNormalizer exact = new TermNormalizer(
                Glossary.parse(TestKb.read("/interview/glossary.txt")), false);
        for (CompiledQuestion cq : TestKb.get().questions()) {
            String ref = cq.question().reference().replaceAll("[A-Za-z0-9_\\-+/.#@()]+", " ");
            assertEquals(exact.normalize(ref, false), n.normalize(ref, false),
                    "ложное фонетическое срабатывание в эталоне #" + cq.id());
        }
    }
}
