package ru.example.vosk;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Запускает Spring Boot сервис распознавания и интервью.
 *
 * @version 1.0.0
 */
@SpringBootApplication(scanBasePackages = "ru.example")
@ConfigurationPropertiesScan("ru.example")
public class VoskSttApplication {
    /**
     * Запускает Spring Boot с параметрами локальной модели.
     *
     * @param args аргументы запуска
     */
    public static void main(String[] args) {
        System.setProperty("jna.encoding", "UTF-8");
        SpringApplication.run(VoskSttApplication.class, args);
    }
}
