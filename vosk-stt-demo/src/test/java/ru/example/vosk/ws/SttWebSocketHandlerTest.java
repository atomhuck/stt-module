package ru.example.vosk.ws;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import ru.example.vosk.service.SpeechSession;
import ru.example.vosk.service.SpeechSessionFactory;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

/** Работает без модели: распознаватель подменён моком. */
class SttWebSocketHandlerTest {

    SpeechSessionFactory factory = mock(SpeechSessionFactory.class);
    SpeechSession speech = mock(SpeechSession.class);
    WebSocketSession ws = mock(WebSocketSession.class);
    SttWebSocketHandler handler = new SttWebSocketHandler(factory);

    @BeforeEach
    void setUp() throws Exception {
        when(factory.create()).thenReturn(speech);
        when(ws.getId()).thenReturn("1");
        when(ws.isOpen()).thenReturn(true);
        handler.afterConnectionEstablished(ws);
    }

    @Test
    void sends_partial_result_to_client() throws Exception {
        when(speech.accept(any(), anyInt())).thenReturn(new SpeechSession.Event("partial", "приве"));

        handler.handleMessage(ws, new BinaryMessage(new byte[]{1, 2, 3, 4}));

        verify(ws).sendMessage(argThat(m -> m instanceof TextMessage t
                && t.getPayload().contains("\"partial\"") && t.getPayload().contains("приве")));
    }

    @Test
    void does_not_send_when_nothing_new() throws Exception {
        when(speech.accept(any(), anyInt())).thenReturn(null);

        handler.handleMessage(ws, new BinaryMessage(new byte[]{1, 2}));

        verify(ws, never()).sendMessage(any());
    }

    @Test
    void eof_returns_final_text_and_closes() throws Exception {
        when(speech.finish()).thenReturn(new SpeechSession.Event("final", "привет мир"));

        handler.handleMessage(ws, new TextMessage("eof"));

        verify(ws).sendMessage(argThat(m -> m instanceof TextMessage t && t.getPayload().contains("привет мир")));
        verify(ws).close(CloseStatus.NORMAL);

        handler.afterConnectionClosed(ws, CloseStatus.NORMAL);
        verify(speech).close();
    }
}
