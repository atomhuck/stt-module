package ru.example.interview.live;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WhisperDetailedOutputTest {
    @Test
    void detailedOutputKeepsLegacyTextEndpoint() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/transcribe", exchange -> {
            byte[] body = "старый текст".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/transcribe-json", exchange -> {
            byte[] body = "{\"text\":\"новый текст\",\"words\":[{\"word\":\"новый\",\"start\":0.2,\"end\":0.5}]}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            var client = new WhisperHttpTranscriber("http://127.0.0.1:" + server.getAddress().getPort()
                    + "/transcribe", Duration.ofSeconds(3));
            assertEquals("старый текст", client.transcribe(new byte[2]).join());
            var detailed = client.transcribeDetailed(new byte[2]).join();
            assertEquals("новый текст", detailed.text());
            assertEquals("новый", detailed.words().get(0).word());
            assertEquals(.2, detailed.words().get(0).start());
        } finally { server.stop(0); }
    }

    @Test
    void oldTextOnlyServerStillRefinesAnswers() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/transcribe", exchange -> {
            boolean oldPath = exchange.getRequestURI().getPath().equals("/transcribe");
            byte[] body = oldPath ? "прежний ответ".getBytes(StandardCharsets.UTF_8) : new byte[0];
            exchange.sendResponseHeaders(oldPath ? 200 : 404, body.length);
            if (body.length > 0) exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            var client = new WhisperHttpTranscriber("http://127.0.0.1:" + server.getAddress().getPort()
                    + "/transcribe", Duration.ofSeconds(3));
            var result = client.transcribeDetailed(new byte[2]).join();
            assertEquals("прежний ответ", result.text());
            assertEquals(0, result.words().size());
        } finally { server.stop(0); }
    }
}
