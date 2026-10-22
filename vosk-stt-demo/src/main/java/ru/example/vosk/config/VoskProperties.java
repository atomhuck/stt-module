package ru.example.vosk.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Хранит путь к модели Vosk и частоту аудио.
 *
 * @version 1.0.0
 */
@ConfigurationProperties(prefix = "vosk")
public record VoskProperties(String modelPath, float sampleRate) {
    public VoskProperties {
        if (modelPath == null || modelPath.isBlank()) {
            modelPath = "./models/vosk-model-ru-0.42";
        }
        if (sampleRate <= 0) {
            sampleRate = 16000f;
        }
    }
}
