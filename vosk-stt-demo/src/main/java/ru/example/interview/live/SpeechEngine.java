package ru.example.interview.live;

 * @version 1.0.0
/** Потоковый распознаватель речи (в приложении — Vosk, в тестах — заглушка). */
public interface SpeechEngine extends AutoCloseable {

    /** @param isFinal true — Vosk сам зафиксировал фразу; false — промежуточная гипотеза */
    record Result(boolean isFinal, String text) {}

    /** Подать кусок PCM 16 кГц / 16 бит / моно. null — ничего нового. */
    Result accept(byte[] pcm, int len);

    /** Дораспознать остаток и сбросить состояние для следующего ответа. */
    Result finish();

    @Override
    void close();
}
