package ru.example.interview.live;

/**
 * Потоковый распознаватель речи (в приложении — Vosk, в тестах — заглушка).
 *
 * @version 1.0.0
 */
public interface SpeechEngine extends AutoCloseable {

    /**
     * Содержит одну гипотезу распознавания.
     *
     * @param isFinal true для зафиксированной фразы, false для промежуточной
     * @param text распознанный текст
     * @version 1.0.0
     */
    record Result(boolean isFinal, String text) {}

    /**
     * Передаёт кадр PCM16 16 кГц моно.
     *
     * @param pcm байты аудиокадра
     * @param len число значимых байтов
     * @return новая гипотеза либо null, если текст не изменился
     */
    Result accept(byte[] pcm, int len);

    /**
     * Дораспознаёт остаток и сбрасывает состояние для следующего ответа.
     *
     * @return итоговая гипотеза либо null при отсутствии новых слов
     */
    Result finish();

    /** Освобождает ресурсы речевого движка. */
    @Override
    void close();
}
