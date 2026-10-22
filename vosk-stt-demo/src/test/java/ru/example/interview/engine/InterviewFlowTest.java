package ru.example.interview.engine;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class InterviewFlowTest {

    private static final DecisionPolicy POLICY = new DecisionPolicy(2, 0.08);
    private static final AnswerTiming QUICK = new AnswerTiming(900, 20000);

    private InterviewSession session(List<Level> levels) {
        return new InterviewSession(TestKb.get(), levels, POLICY);
    }

    @Test
    void bank_is_loaded_with_followups_for_every_question() {
        InterviewKnowledge kb = TestKb.get();
        assertEquals(50, kb.questions().length);
        for (CompiledQuestion q : kb.questions()) {
            assertTrue(q.followUps().length >= 4, "мало уточняющих у вопроса " + q.id());
            assertTrue(q.poolSize() >= 5, "слишком маленький пул понятий у вопроса " + q.id());
        }
    }

    @Test
    void strong_answer_moves_on_without_followup() {
        InterviewSession s = session(List.of(Level.INTERN));
        InterviewSession.Prompt p = s.start();
        CompiledQuestion cq = TestKb.get().byId(p.questionId());
        InterviewSession.Step step = s.submit(cq.question().reference(), QUICK);
        assertEquals(InterviewSession.Action.NEXT_QUESTION, step.action());
        assertEquals("sufficient", step.reason());
    }

    @Test
    void partial_answer_gets_followup_that_targets_missing_concepts() {
        InterviewSession s = session(List.of(Level.INTERN));
        InterviewSession.Prompt p = s.start();   // «Что такое JVM, JRE и JDK?»
        InterviewSession.Step step = s.submit("джава виртуальная машина она запускает программы а джи ди кей это для разработки там компилятор", QUICK);
        assertEquals(InterviewSession.Action.ASK_FOLLOW_UP, step.action());
        assertTrue(step.next().followUp());
        assertEquals(1, step.next().followUpNumber());
        assertEquals(p.questionId(), step.next().questionId());
    }

    @Test
    void ignorance_is_not_pressed() {
        InterviewSession s = session(List.of(Level.INTERN));
        s.start();
        InterviewSession.Step step = s.submit("не знаю не помню", QUICK);
        assertEquals(InterviewSession.Action.NEXT_QUESTION, step.action());
        assertEquals("ignorance", step.reason());
    }

    @Test
    void empty_answer_moves_on() {
        InterviewSession s = session(List.of(Level.INTERN));
        s.start();
        assertEquals("no_answer", s.submit("", new AnswerTiming(15000, 0)).reason());
    }

    @Test
    void followup_budget_is_respected() {
        InterviewSession s = session(List.of(Level.INTERN));
        s.start();
        int followUps = 0;
        InterviewSession.Step step = s.submit("ну это что-то про джаву и программы вроде", QUICK);
        while (step.action() == InterviewSession.Action.ASK_FOLLOW_UP) {
            followUps++;
            step = s.submit("ну это что-то про джаву и программы вроде", QUICK);
        }
        assertTrue(followUps <= 2, "уточнений: " + followUps);
    }

    private InterviewReport run(String mode) {
        InterviewSession s = session(null);
        InterviewSession.Prompt p = s.start();
        int guard = 0;
        while (p != null && guard++ < 1000) {
            CompiledQuestion cq = TestKb.get().byId(p.questionId());
            String answer = switch (mode) {
                case "strong" -> p.followUp() ? cq.question().followUps().get(0) + " " + cq.question().reference()
                        : cq.question().reference() + " " + cq.question().notes();
                case "weak" -> "ну это вроде как-то типа не знаю";
                default -> "";
            };
            AnswerTiming timing = mode.equals("strong") ? new AnswerTiming(1000, 25000) : new AnswerTiming(9000, 3000);
            p = s.submit(answer, timing).next();
        }
        assertTrue(s.isFinished());
        return s.report();
    }

    @Test
    void full_interview_scores_are_ordered_and_bounded() {
        InterviewReport strong = run("strong");
        InterviewReport weak = run("weak");
        InterviewReport silent = run("none");

        assertEquals(50, strong.answeredQuestions());
        assertFalse(strong.finishedEarly());
        assertTrue(strong.total() > weak.total() + 3, strong.totalText() + " vs " + weak.totalText());
        assertTrue(weak.total() >= silent.total());
        for (InterviewReport r : List.of(strong, weak, silent)) {
            for (double c : new double[]{r.completeness(), r.technical(), r.terminology(), r.speed(), r.total()}) {
                assertTrue(c >= 0 && c <= 10, "вне шкалы: " + c);
            }
            assertTrue(r.totalText().matches("\\d{1,2}\\.\\d{2}"), r.totalText());
        }
        assertEquals(0.0, silent.total(), 0.001);
    }

    @Test
    void fast_non_answers_do_not_earn_speed_points() {
        InterviewReport weak = run("weak");
        InterviewReport strong = run("strong");
        assertTrue(weak.speed() < 3.5, "скорость пустых ответов: " + weak.speed());
        assertTrue(strong.speed() > 9, "скорость сильных ответов: " + strong.speed());
    }

    @Test
    void total_is_exact_average_of_four_rounded_criteria() {
        InterviewReport r = run("strong");
        double expected = Math.round((r.completeness() + r.technical() + r.terminology() + r.speed()) / 4.0 * 100) / 100.0;
        assertEquals(expected, r.total(), 0.0051);
    }

    @Test
    void slow_candidate_gets_lower_speed_score() {
        InterviewSession fast = session(List.of(Level.INTERN));
        InterviewSession slow = session(List.of(Level.INTERN));
        for (InterviewSession s : List.of(fast, slow)) {
            InterviewSession.Prompt p = s.start();
            while (p != null) {
                CompiledQuestion cq = TestKb.get().byId(p.questionId());
                AnswerTiming t = s == fast ? new AnswerTiming(1000, 25000) : new AnswerTiming(14000, 25000);
                p = s.submit(cq.question().reference(), t).next();
            }
        }
        assertTrue(fast.report().speed() > slow.report().speed() + 3);
    }

    @Test
    void early_stop_reports_only_answered_questions() {
        InterviewSession s = session(null);
        InterviewSession.Prompt p = s.start();
        s.submit(TestKb.get().byId(p.questionId()).question().reference(), QUICK);
        InterviewReport r = s.report();
        assertTrue(r.finishedEarly());
        assertEquals(1, r.answeredQuestions());
        assertEquals(50, r.plannedQuestions());
    }

    @Test
    void level_filter_limits_plan_and_orders_levels() {
        InterviewSession s = session(List.of(Level.SENIOR, Level.INTERN));
        InterviewSession.Prompt p = s.start();
        assertEquals(Level.INTERN.title, p.level());
        int senior = 0;
        for (CompiledQuestion q : TestKb.get().questions()) if (q.level() == Level.SENIOR) senior++;
        assertTrue(p.total() > senior);
    }

    @Test
    void terminology_rewards_professional_vocabulary_incl_slang() {
        InterviewKnowledge kb = TestKb.get();
        CompiledQuestion q = kb.byId(1);
        AnswerAnalyzer an = kb.analyzer();
        AnswerAnalysis pro = an.analyze(q, "задеплоили на прод байткод исполняет джей ви эм а джи ди кей с компилятором джавак", new boolean[q.poolSize()]);
        AnswerAnalysis plain = an.analyze(q, "это такая штука чтобы запускать программы которые написали люди", new boolean[q.poolSize()]);
        assertTrue(pro.termTokens() >= 4);
        assertEquals(0, plain.termTokens());
    }

    @Test
    void more_accurate_text_replaces_less_accurate_but_not_vice_versa() {
        InterviewSession s = session(List.of(Level.INTERN));
        InterviewSession.Prompt p = s.start();
        CompiledQuestion cq = TestKb.get().byId(p.questionId());
        s.submit("ну что-то про джаву", QUICK);
        InterviewSession.AttemptRef ref = s.lastAttempt();
        assertEquals(0, s.report().questions().get(0).coveragePercent() > 20 ? 1 : 0);

        s.refine(ref, cq.question().reference(), 2);                    // «Whisper» дал точный текст
        int after = s.report().questions().get(0).coveragePercent();
        assertTrue(after >= 50, "покрытие после уточнения: " + after);

        s.refine(ref, "не знаю", 1);                                     // менее точный источник не затирает
        assertEquals(after, s.report().questions().get(0).coveragePercent());
    }

    @Test
    void peek_predicts_the_decision_without_changing_the_session() {
        InterviewSession s = session(List.of(Level.INTERN));
        InterviewSession.Prompt p = s.start();
        CompiledQuestion cq = TestKb.get().byId(p.questionId());

        InterviewSession.Peek weak = s.peek("джава виртуальная машина она запускает программы");
        assertTrue(weak.followUp());
        InterviewSession.Peek strong = s.peek(cq.question().reference());
        assertFalse(strong.followUp());
        assertEquals("sufficient", strong.reason());
        // сессия не менялась: отчёт пуст, а настоящий submit даёт то же, что обещал peek
        assertEquals(0, s.report().answeredQuestions());
        InterviewSession.Step real = s.submit(cq.question().reference(), QUICK);
        assertEquals(InterviewSession.Action.NEXT_QUESTION, real.action());
        assertEquals(strong.coverage(), real.coverage(), 1e-9);
    }

    @Test
    void undo_restores_the_state_before_a_follow_up_was_asked() {
        InterviewSession s = session(List.of(Level.INTERN));
        InterviewSession.Prompt first = s.start();
        InterviewSession.Step step = s.submit("джава виртуальная машина она запускает программы", QUICK);
        assertEquals(InterviewSession.Action.ASK_FOLLOW_UP, step.action());

        InterviewSession.Prompt back = s.undoLast();
        assertEquals(first.text(), back.text());
        assertFalse(back.followUp());
        assertEquals(0, s.report().answeredQuestions());      // ответ снова «не принят»

        // и дальше интервью идёт как ни в чём не бывало: полный ответ → следующий вопрос, без «следа» уточнения
        CompiledQuestion cq = TestKb.get().byId(first.questionId());
        InterviewSession.Step again = s.submit(cq.question().reference(), QUICK);
        assertEquals(InterviewSession.Action.NEXT_QUESTION, again.action());
        InterviewReport r = s.report();
        assertEquals(1, r.answeredQuestions());
        assertEquals(1, r.questions().get(0).attempts());
    }

    @Test
    void undo_restores_the_previous_question_after_moving_on() {
        InterviewSession s = session(List.of(Level.INTERN));
        InterviewSession.Prompt first = s.start();
        CompiledQuestion cq = TestKb.get().byId(first.questionId());
        InterviewSession.Step step = s.submit(cq.question().reference(), QUICK);
        assertEquals(2, step.next().number());

        InterviewSession.Prompt back = s.undoLast();
        assertEquals(1, back.number());
        assertEquals(first.text(), back.text());
        assertNull(s.undoLast(), "откатывать можно только последнее решение");
    }

    @Test
    void undo_after_the_last_question_reopens_it() {
        InterviewSession s = new InterviewSession(TestKb.single(), null, POLICY);
        InterviewSession.Prompt p = s.start();
        CompiledQuestion cq = TestKb.single().byId(p.questionId());
        InterviewSession.Step st = s.submit(cq.question().reference(), QUICK);
        assertEquals(InterviewSession.Action.FINISH, st.action());
        assertTrue(s.isFinished());

        InterviewSession.Prompt back = s.undoLast();
        assertNotNull(back);
        assertFalse(s.isFinished());
        assertEquals(InterviewSession.Action.FINISH, s.submit(cq.question().reference(), QUICK).action());
    }
}
