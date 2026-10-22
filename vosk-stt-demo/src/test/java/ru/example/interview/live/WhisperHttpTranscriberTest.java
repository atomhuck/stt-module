package ru.example.interview.live;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Клиент Whisper-сервера проверяется на настоящем локальном HTTP-сервере (JDK). */
class WhisperHttpTranscriberTest {

    @Test
    void sends_raw_pcm_and_returns_utf8_text() throws Exception {
        final int[] received = {0};
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/transcribe", ex -> {
            received[0] = ex.getRequestBody().readAllBytes().length;
            byte[] body = "JVM — это виртуальная машина".getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "text/plain; charset=utf-8");
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.start();
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/transcribe";
            String text = new WhisperHttpTranscriber(url, Duration.ofSeconds(5)).transcribe(new byte[64000]).get(5, TimeUnit.SECONDS);
            assertEquals("JVM — это виртуальная машина", text);
            assertEquals(64000, received[0]);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void server_error_becomes_failed_future() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/transcribe", ex -> {
            ex.sendResponseHeaders(500, -1);
            ex.close();
        });
        server.start();
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/transcribe";
            boolean failed = false;
            try {
                new WhisperHttpTranscriber(url, Duration.ofSeconds(5)).transcribe(new byte[100]).get(5, TimeUnit.SECONDS);
            } catch (java.util.concurrent.ExecutionException e) {
                failed = true;
                assertTrue(e.getCause().getMessage().contains("500"));
            }
            assertTrue(failed);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void unreachable_server_fails_without_hanging() throws Exception {
        boolean failed = false;
        try {
            new WhisperHttpTranscriber("http://127.0.0.1:1/transcribe", Duration.ofSeconds(2)).transcribe(new byte[10]).get(5, TimeUnit.SECONDS);
        } catch (java.util.concurrent.ExecutionException e) {
            failed = true;
        }
        assertTrue(failed);
    }
}
