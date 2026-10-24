package ru.example.interview.live;

import java.util.concurrent.CompletableFuture;

/**
 * Второй, более точный проход распознавания (Whisper). Работает асинхронно, вне критического пути решения.
 *
 * @version 1.0.0
 */
public interface Transcriber {
    /**
     * Уточняет распознавание без блокировки основного решения интервью.
     *
     * @param pcm16kMono PCM16 little-endian, 16 кГц, моно
     * @return асинхронный уточнённый текст
     */
    CompletableFuture<String> transcribe(byte[] pcm16kMono);
}
