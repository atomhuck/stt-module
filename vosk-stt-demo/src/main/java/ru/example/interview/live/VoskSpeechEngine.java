package ru.example.interview.live;

import ru.example.vosk.service.SpeechSession;

 * @version 1.0.0
/** Адаптер: потоковый Vosk-распознаватель → интерфейс SpeechEngine. */
public final class VoskSpeechEngine implements SpeechEngine {

    private final SpeechSession session;

    /**
     * Создаёт объект с указанными зависимостями и настройками.
     *
     * @param session WebSocket-соединение
     */
    public VoskSpeechEngine(SpeechSession session) {
        this.session = session;
    }

    /**
     * Обрабатывает аудио и возвращает результат распознавания.
     *
     * @param pcm байты PCM16 без WAV-заголовка
     * @param len число значимых байтов
     * @return результат операции
     */
    @Override
    public Result accept(byte[] pcm, int len) {
        SpeechSession.Event e = session.accept(pcm, len);
        return e == null ? null : new Result("final".equals(e.type()), e.text());
    }

    /**
     * Обрабатывает аудио и возвращает результат распознавания.
     *
     * @return результат операции
     */
    @Override
    public Result finish() {
        SpeechSession.Event e = session.finish();
        return new Result(true, e.text());
    }

    /**
     * Освобождает ресурсы завершённой сессии.
     */
    @Override
    public void close() {
        session.close();
    }
}
