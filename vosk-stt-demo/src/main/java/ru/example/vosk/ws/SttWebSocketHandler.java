package ru.example.vosk.ws;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;
import ru.example.vosk.service.SpeechSession;
import ru.example.vosk.service.SpeechSessionFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Протокол:
 *  клиент → сервер: binary-кадры с PCM 16 кГц / 16 бит / моно; text "eof" — конец записи
 *  сервер → клиент: text JSON {"type":"partial"|"final","text":"..."}
 * @version 1.0.0
 */
@Component
public class SttWebSocketHandler extends AbstractWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(SttWebSocketHandler.class);

    private final SpeechSessionFactory factory;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, SpeechSession> sessions = new ConcurrentHashMap<>();

    /**
     * Подключает WebSocket-канал потокового Vosk.
     *
     * @param factory фабрика речевых сессий
     */
    public SttWebSocketHandler(SpeechSessionFactory factory) {
        this.factory = factory;
    }

    /**
     * Выделяет распознаватель Vosk новому соединению.
     *
     * @param session WebSocket-соединение
     * @throws Exception если операция или подключённый сервис завершились ошибкой
     */
    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        sessions.put(session.getId(), factory.create());
        log.info("Микрофон подключён: {}", session.getId());
    }

    /**
     * Передаёт PCM-кадр распознавателю данного соединения.
     *
     * @param session WebSocket-соединение
     * @param message полученное сообщение
     * @throws IOException при ошибке чтения, записи или обработки потока
     */
    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) throws IOException {
        SpeechSession speech = sessions.get(session.getId());
        if (speech == null) return;

        ByteBuffer buf = message.getPayload();
        byte[] data = new byte[buf.remaining()];
        buf.get(data);

        SpeechSession.Event event = speech.accept(data, data.length);
        if (event != null) {
            send(session, event);
        }
    }

    /**
     * Обрабатывает маркер eof и закрывает речевую сессию.
     *
     * @param session WebSocket-соединение
     * @param message полученное сообщение
     * @throws IOException при ошибке чтения, записи или обработки потока
     */
    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws IOException {
        if ("eof".equals(message.getPayload().trim())) {
            SpeechSession speech = sessions.get(session.getId());
            if (speech != null) {
                send(session, speech.finish());
            }
            session.close(CloseStatus.NORMAL);
        }
    }

    /**
     * Обрабатывает событие активной сессии.
     *
     * @param session WebSocket-соединение
     * @param exception причина ошибки
     */
    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.warn("Ошибка WebSocket {}: {}", session.getId(), exception.toString());
    }

    /**
     * Освобождает распознаватель после закрытия соединения.
     *
     * @param session WebSocket-соединение
     * @param status код закрытия соединения
     */
    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        SpeechSession speech = sessions.remove(session.getId());
        if (speech != null) {
            speech.close();
        }
        log.info("Микрофон отключён: {}", session.getId());
    }

    private void send(WebSocketSession session, SpeechSession.Event event) throws IOException {
        if (session.isOpen()) {
            session.sendMessage(new TextMessage(mapper.writeValueAsString(event)));
        }
    }
}
