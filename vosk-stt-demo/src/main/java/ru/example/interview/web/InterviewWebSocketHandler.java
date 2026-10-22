package ru.example.interview.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;
import ru.example.interview.engine.DecisionPolicy;
import ru.example.interview.engine.EndpointDetector;
import ru.example.interview.engine.InterviewKnowledge;
import ru.example.interview.engine.InterviewSession;
import ru.example.interview.engine.Level;
import ru.example.interview.live.LiveInterview;
import ru.example.interview.live.SpeechEngine;
import ru.example.interview.live.Transcriber;
import ru.example.interview.live.WhisperHttpTranscriber;
import ru.example.interview.live.VoskSpeechEngine;
import ru.example.vosk.service.SpeechSessionFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * /ws/interview
 *  клиент → сервер (text JSON): {"type":"start","levels":["Junior"]} | {"type":"ready"} | {"type":"skip"} | {"type":"stop"}
 *  клиент → сервер (binary):    PCM 16 кГц / 16 бит / моно, кадры по ~50 мс
 *  сервер → клиент (text JSON): question | partial | decision | report | error
 * @version 1.0.0
 */
@Component
public class InterviewWebSocketHandler extends AbstractWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(InterviewWebSocketHandler.class);

    private record Active(LiveInterview live, SpeechEngine engine) {}

    private final SpeechSessionFactory speechFactory;
    private final InterviewKnowledge kb;
    private final DecisionPolicy policy;
    private final InterviewProperties props;
    private final ExcelReportService reports;
    private final ObjectMapper mapper;
    private final Map<String, Active> active = new ConcurrentHashMap<>();
    private final Transcriber transcriber;   // null — второй проход (Whisper) выключен

    /**
     * Создаёт объект с указанными зависимостями и настройками.
     *
     * @param speechFactory параметр операции
     * @param kb скомпилированный банк знаний
     * @param policy политика выбора следующего действия
     * @param props настройки приложения
     * @param mapper параметр операции
     * @param reports сервис файлов отчётов
     */
    public InterviewWebSocketHandler(SpeechSessionFactory speechFactory, InterviewKnowledge kb,
                                     DecisionPolicy policy, InterviewProperties props, ObjectMapper mapper,
                                     ExcelReportService reports) {
        this.speechFactory = speechFactory;
        this.kb = kb;
        this.policy = policy;
        this.props = props;
        this.mapper = mapper;
        this.reports = reports;
        String url = props.whisperUrl();
        this.transcriber = (url == null || url.isBlank()) ? null : new WhisperHttpTranscriber(url, Duration.ofSeconds(60));
        log.info("Второй проход распознавания (Whisper): {}", transcriber == null ? "выключен" : url);
    }

    /**
     * Обрабатывает управляющее сообщение активного интервью.
     *
     * @param ws WebSocket-соединение
     * @param message полученное сообщение
     * @throws IOException при ошибке чтения, записи или обработки потока
     */
    @Override
    protected void handleTextMessage(WebSocketSession ws, TextMessage message) throws IOException {
        JsonNode n = mapper.readTree(message.getPayload());
        String type = n.path("type").asText();
        try {
            if ("start".equals(type)) {
                start(ws, n);
                return;
            }
            Active a = active.get(ws.getId());
            if (a == null) return;
            switch (type) {
                case "ready" -> a.live().onReady();
                case "peek" -> a.live().onPeek(n.path("text").asText(""), n.path("requestId").asText(""));
                case "tts_end" -> a.live().onTtsEnd(n.path("afterMs").asLong(0));
                case "skip" -> a.live().onSkip();
                case "stop" -> a.live().onStop();
                default -> { }
            }
        } catch (RuntimeException e) {
            log.warn("Ошибка обработки '{}': {}", type, e.toString());
            send(ws, Map.of("type", "error", "message", String.valueOf(e.getMessage())));
        }
    }

    /**
     * Передаёт PCM-кадр активному интервью.
     *
     * @param ws WebSocket-соединение
     * @param message полученное сообщение
     */
    @Override
    protected void handleBinaryMessage(WebSocketSession ws, BinaryMessage message) {
        Active a = active.get(ws.getId());
        if (a == null) return;
        ByteBuffer buf = message.getPayload();
        byte[] data = new byte[buf.remaining()];
        buf.get(data);
        try {
            a.live().onAudio(data, data.length);
        } catch (RuntimeException e) {
            log.warn("Ошибка обработки аудио: {}", e.toString());
        }
    }

    private void start(WebSocketSession ws, JsonNode n) throws IOException {
        if (active.containsKey(ws.getId())) return;
        List<Level> levels = new ArrayList<>();
        for (JsonNode l : n.path("levels")) levels.add(Level.parse(l.asText()));

        EndpointDetector.Config cfg = new EndpointDetector.Config();
        cfg.baseSilenceMs = props.endSilenceMs();
        cfg.noSpeechTimeoutMs = props.noSpeechTimeoutMs();

        SpeechEngine engine = new VoskSpeechEngine(speechFactory.create());
        LiveInterview live = new LiveInterview(engine, new InterviewSession(kb, levels, policy),
                new EndpointDetector(cfg), msg -> send(ws, msg));
        if (transcriber != null) live.setTranscriber(transcriber, props.whisperWaitMs());
        live.setTiming(new LiveInterview.Timing(props.nextExtraMs(), props.otherNextExtraMs(), props.followUpExtraMs(), props.graceMs()));
        live.setExport(reports, n.path("candidate").asText(""), settings());
        active.put(ws.getId(), new Active(live, engine));
        live.start();
        send(ws, Map.of("type", "service_status", "whisper",
                transcriber == null ? "disabled" : "configured",
                "whisperUrl", transcriber == null ? "" : props.whisperUrl()));
    }

    /** Параметры интервью для листа «Методика» в Excel-отчёте. */
    private Map<String, String> settings() {
        Map<String, String> m = new java.util.LinkedHashMap<>();
        m.put("Распознавание речи", "Vosk (потоковое, русская модель)");
        m.put("Порог тишины (мягкий конец ответа)", props.endSilenceMs() + " мс");
        m.put("Доп. пауза до решения: достаточный ответ / «не знаю» / нужно уточнение",
                props.nextExtraMs() + " / " + props.otherNextExtraMs() + " / " + props.followUpExtraMs() + " мс");
        m.put("Окно отмены решения", props.graceMs() + " мс");
        m.put("Макс. уточняющих вопросов на вопрос", String.valueOf(props.maxFollowUps()));
        m.put("Порог «слабого» ответа (покрытие)", Math.round(props.weakCoverage() * 100) + " %");
        m.put("Таймаут молчания (нет ответа)", props.noSpeechTimeoutMs() / 1000 + " с");
        m.put("Второй проход (Whisper)", transcriber == null ? "выключен" : "включён: " + props.whisperUrl());
        return m;
    }

    /**
     * Выполняет операцию публичного контракта компонента.
     *
     * @param ws WebSocket-соединение
     * @param status код закрытия соединения
     */
    @Override
    public void afterConnectionClosed(WebSocketSession ws, CloseStatus status) {
        Active a = active.remove(ws.getId());
        if (a != null) a.engine().close();
    }

    /**
     * Обрабатывает событие активной сессии.
     *
     * @param ws WebSocket-соединение
     * @param ex причина ошибки
     */
    @Override
    public void handleTransportError(WebSocketSession ws, Throwable ex) {
        log.warn("Ошибка WebSocket {}: {}", ws.getId(), ex.toString());
    }

    /** Whisper отвечает из другого потока, а WebSocketSession не терпит параллельной записи — поэтому синхронизируем. */
    private void send(WebSocketSession ws, Map<String, ?> msg) {
        try {
            String json = mapper.writeValueAsString(msg);
            synchronized (ws) {
                if (ws.isOpen()) ws.sendMessage(new TextMessage(json));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
