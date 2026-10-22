package ru.example.vosk.service;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.vosk.LibVosk;
import org.vosk.LogLevel;
import org.vosk.Model;

import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Тест «голос → текст» на настоящей модели: WAV подаётся кусками по 100 мс, как с микрофона.
 * Пропускается, если модель не найдена (./models/vosk-model-ru-0.42 или $VOSK_MODEL_PATH).
 * Аудио: короткая синтетическая фраза. Тест проверяет загрузку модели и итогового события, а не качество распознавания.
 */
@EnabledIf("modelInstalled")
class SpeechSessionModelTest {

    static Model model;

    static {
        System.setProperty("jna.encoding", "UTF-8"); // см. VoskSttApplication
    }

    static Path modelPath() {
        String env = System.getenv("VOSK_MODEL_PATH");
        return Path.of(env != null && !env.isBlank() ? env : "./models/vosk-model-ru-0.42");
    }

    static boolean modelInstalled() {
        return Files.isDirectory(modelPath().resolve("am"));
    }

    @BeforeAll
    static void load() throws IOException {
        LibVosk.setLogLevel(LogLevel.WARNINGS);
        model = new Model(modelPath().toAbsolutePath().toString());
    }

    @AfterAll
    static void unload() {
        if (model != null) model.close();
    }

    @Test
    void streams_audio_like_microphone() throws Exception {
        List<String> finals = new ArrayList<>();
        int partials = 0;

        try (AudioInputStream ais = AudioSystem.getAudioInputStream(getClass().getResource("/test-ru.wav"));
             SpeechSession session = new SpeechSession(model, 16000f)) {

            byte[] chunk = new byte[3200]; // 100 мс: 16000 * 2 байта * 0.1
            int n;
            while ((n = ais.read(chunk)) > 0) {
                SpeechSession.Event e = session.accept(chunk, n);
                if (e == null) continue;
                if (e.type().equals("partial")) partials++;
                else finals.add(e.text());
            }
            SpeechSession.Event last = session.finish();
            if (!last.text().isEmpty()) finals.add(last.text());
        }

        String text = String.join(" ", finals).toLowerCase();
        System.out.println("Промежуточных обновлений: " + partials + ", итог: " + text);

        assertFalse(finals.isEmpty(), "После подачи чанков должен прийти итоговый результат Vosk");
        assertFalse(text.isBlank(), "Итоговый текст не должен быть пустым");
    }
}
