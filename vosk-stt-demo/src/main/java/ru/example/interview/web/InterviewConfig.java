package ru.example.interview.web;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import ru.example.interview.engine.DecisionPolicy;
import ru.example.interview.engine.Glossary;
import ru.example.interview.engine.InterviewKnowledge;
import ru.example.interview.engine.QuestionBank;
import ru.example.interview.engine.SpokenText;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Создаёт банк знаний и политику принятия решений для интервью.
 *
 * @version 1.0.0
 */
@Configuration
public class InterviewConfig {

    /**
     * Загружает банк вопросов и словарь, затем прогревает анализатор.
     *
     * @param p настройки интервью
     * @return подготовленный банк знаний
     * @throws IOException при ошибке чтения, записи или обработки потока
     */
    @Bean
    public InterviewKnowledge interviewKnowledge(InterviewProperties p) throws IOException {
        String tsv = load(p.questionsPath(), "interview/questions.tsv");
        String glossary = load(p.glossaryPath(), "interview/glossary.txt");
        InterviewKnowledge kb = InterviewKnowledge.build(QuestionBank.parse(tsv), Glossary.parse(glossary))
                .withSpoken(SpokenText.parse(load(p.spokenPath(), "interview/spoken.txt")));
        kb.warmUp(30);   // прогрев JIT: первое реальное решение не должно быть «холодным»
        return kb;
    }

    /**
     * Создаёт политику переходов по порогам из конфигурации.
     *
     * @param p настройки интервью
     * @return политика оценки ответа
     */
    @Bean
    public DecisionPolicy decisionPolicy(InterviewProperties p) {
        return new DecisionPolicy(p.maxFollowUps(), p.weakCoverage());
    }

    private static String load(String external, String classpath) throws IOException {
        if (external != null && !external.isBlank()) {
            return Files.readString(Path.of(external), StandardCharsets.UTF_8);
        }
        try (InputStream in = new ClassPathResource(classpath).getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
