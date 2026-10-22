package ru.example.interview.live;

import java.util.concurrent.CompletableFuture;

 * @version 1.0.0
/** Второй, более точный проход распознавания (Whisper). Работает асинхронно, вне критического пути решения. */
public interface Transcriber {
    /** @param pcm16kMono PCM 16 кГц / 16 бит / little-endian / моно */
    CompletableFuture<String> transcribe(byte[] pcm16kMono);
}
