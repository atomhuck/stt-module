package ru.example.vosk.config;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.vosk.LibVosk;
import org.vosk.LogLevel;
import org.vosk.Model;
import org.vosk.Recognizer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Загружает модель Vosk один раз при старте приложения.
 * Model потокобезопасна и используется всеми запросами; Recognizer создаётся на каждый запрос.
 * @version 1.0.0
 */
@Component
public class VoskModelHolder {

    private static final Logger log = LoggerFactory.getLogger(VoskModelHolder.class);

    private final VoskProperties props;
    private Model model;

    /**
     * Настраивает загрузку локальной модели Vosk.
     *
     * @param props настройки приложения
     */
    public VoskModelHolder(VoskProperties props) {
        this.props = props;
    }

    @PostConstruct
    void load() throws IOException {
        Path path = Path.of(props.modelPath()).toAbsolutePath().normalize();
        if (!Files.isDirectory(path) || !Files.isDirectory(path.resolve("am"))) {
            throw new IllegalStateException("""
                    Модель Vosk не найдена: %s
                    Скачайте и распакуйте vosk-model-ru-0.42 (скрипт download-model.sh / download-model.ps1)
                    или задайте путь: VOSK_MODEL_PATH=/путь/к/vosk-model-ru-0.42""".formatted(path));
        }
        LibVosk.setLogLevel(LogLevel.WARNINGS);
        log.info("Загрузка модели Vosk из {} (для ru-0.42 это может занять до минуты)...", path);
        long t = System.currentTimeMillis();
        this.model = new Model(path.toString());
        log.info("Модель загружена за {} мс", System.currentTimeMillis() - t);
        warmUp();
    }

    /** Прогрев: прогоняем 2 секунды тишины, чтобы первый реальный ответ не платил за «холодный» старт нативного кода. */
    private void warmUp() {
        long t = System.currentTimeMillis();
        try (Recognizer r = new Recognizer(model, props.sampleRate())) {
            byte[] silence = new byte[(int) props.sampleRate() * 2 * 2];
            r.acceptWaveForm(silence, silence.length);
            r.getFinalResult();
            log.info("Прогрев распознавателя: {} мс", System.currentTimeMillis() - t);
        } catch (Exception e) {
            log.warn("Прогрев не удался: {}", e.toString());
        }
    }

    /**
     * Возвращает загруженную модель Vosk.
     *
     * @return загруженная модель Vosk
     */
    public Model getModel() {
        return model;
    }

    @PreDestroy
    void close() {
        if (model != null) {
            model.close();
        }
    }
}
