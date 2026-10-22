package ru.example.interview.live;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.ArrayList;
import java.util.List;

/**
 * Клиент локального Whisper-сервера (tools/whisper_server.py).
 * Протокол: POST {url} с телом = сырой PCM (16 кГц, 16 бит, моно); ответ 200 — текст UTF-8.
 * @version 1.0.0
 */
public final class WhisperHttpTranscriber implements Transcriber {

    /**
     * Хранит слово с временными метками и оценкой распознавания.
     *
     * @version 1.0.0
     */
    public record Word(String word, double start, double end, Double confidence) {}
    /**
     * Содержит уточнённый текст и размеченные слова.
     *
     * @version 1.0.0
     */
    public record Transcript(String text, List<Word> words) {}
    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final URI uri;
    private final URI detailedUri;
    private final Duration timeout;

    /**
     * Создаёт объект с указанными зависимостями и настройками.
     *
     * @param url адрес локального HTTP-сервиса
     * @param timeout таймаут HTTP-запроса
     */
    public WhisperHttpTranscriber(String url, Duration timeout) {
        this.uri = URI.create(url);
        this.detailedUri = URI.create(url.replaceFirst("/transcribe/?$", "/transcribe-json"));
        this.timeout = timeout;
    }

    /**
     * Отправляет PCM в локальный Whisper и получает текст ответа.
     *
     * @param pcm байты PCM16 без WAV-заголовка
     * @return асинхронный результат распознавания
     */
    @Override
    public CompletableFuture<String> transcribe(byte[] pcm) {
        HttpRequest req = HttpRequest.newBuilder(uri)
                .timeout(timeout)
                .header("Content-Type", "application/octet-stream")
                .POST(HttpRequest.BodyPublishers.ofByteArray(pcm))
                .build();
        return client.sendAsync(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .thenApply(r -> {
                    if (r.statusCode() != 200) {
                        throw new IllegalStateException("Whisper-сервер вернул HTTP " + r.statusCode());
                    }
                    return r.body().trim();
                });
    }

     * @param pcm байты PCM16 без WAV-заголовка
     * @return асинхронный результат распознавания
    /** Optional enriched output. The original text-only /transcribe endpoint remains unchanged. */
    public CompletableFuture<Transcript> transcribeDetailed(byte[] pcm) {
        HttpRequest req = HttpRequest.newBuilder(detailedUri).timeout(timeout)
                .header("Content-Type", "application/octet-stream")
                .POST(HttpRequest.BodyPublishers.ofByteArray(pcm)).build();
        return client.sendAsync(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .thenCompose(response -> {
                    if (response.statusCode() == 404)
                        return transcribe(pcm).thenApply(text -> new Transcript(text, List.of()));
                    if (response.statusCode() != 200)
                        throw new IllegalStateException("Whisper-сервер вернул HTTP " + response.statusCode());
                    try {
                        var root = JSON.readTree(response.body());
                        List<Word> words = new ArrayList<>();
                        for (var row : root.path("words")) {
                            words.add(new Word(row.path("word").asText(""), row.path("start").asDouble(),
                                    row.path("end").asDouble(),
                                    row.hasNonNull("confidence") ? row.path("confidence").asDouble() : null));
                        }
                        return CompletableFuture.completedFuture(new Transcript(root.path("text").asText(""), words));
                    } catch (Exception error) {
                        throw new IllegalStateException("Некорректный JSON от Whisper", error);
                    }
                });
    }
}
