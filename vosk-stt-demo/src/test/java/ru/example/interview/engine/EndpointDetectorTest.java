package ru.example.interview.engine;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EndpointDetectorTest {

    /** Возвращает, через сколько мс тишины после речи сработал конец ответа (или -1). */
    private long silenceUntilEndpoint(int words, boolean connector) {
        EndpointDetector d = new EndpointDetector(new EndpointDetector.Config());
        for (int i = 0; i < 8; i++) d.onFrame(Pcm.frame(false), Pcm.FRAME_SAMPLES * 2, 0, false);   // 400 мс тишины
        for (int i = 0; i < 40; i++) {                                                               // 2 с речи
            d.onFrame(Pcm.frame(true), Pcm.FRAME_SAMPLES * 2, words, connector);
        }
        for (int i = 0; i < 100; i++) {
            if (d.onFrame(Pcm.frame(false), Pcm.FRAME_SAMPLES * 2, words, connector) == EndpointDetector.State.ENDPOINT) {
                return d.silenceMs();
            }
        }
        return -1;
    }

    @Test
    void normal_answer_ends_after_base_silence() {
        long ms = silenceUntilEndpoint(30, false);
        assertTrue(ms >= 450 && ms <= 500, "ожидали ~450 мс, получили " + ms);
    }

    @Test
    void waits_longer_after_connector_word() {
        long ms = silenceUntilEndpoint(30, true);
        assertTrue(ms >= 800 && ms <= 850, "ожидали ~800 мс, получили " + ms);
    }

    @Test
    void waits_longer_for_very_short_answer() {
        long ms = silenceUntilEndpoint(2, false);
        assertTrue(ms >= 800 && ms <= 850, "ожидали ~800 мс, получили " + ms);
    }

    @Test
    void measures_latency_and_duration_of_speech() {
        EndpointDetector d = new EndpointDetector(new EndpointDetector.Config());
        for (int i = 0; i < 40; i++) d.onFrame(Pcm.frame(false), Pcm.FRAME_SAMPLES * 2, 0, false);  // 2 с молчания
        for (int i = 0; i < 60; i++) d.onFrame(Pcm.frame(true), Pcm.FRAME_SAMPLES * 2, 20, false);  // 3 с речи
        for (int i = 0; i < 20; i++) d.onFrame(Pcm.frame(false), Pcm.FRAME_SAMPLES * 2, 20, false);
        assertTrue(Math.abs(d.speechStartMs() - 2000) <= 50, "старт речи: " + d.speechStartMs());
        assertTrue(Math.abs(d.speechDurationMs() - 3000) <= 100, "длительность: " + d.speechDurationMs());
    }

    @Test
    void no_speech_timeout() {
        EndpointDetector.Config c = new EndpointDetector.Config();
        c.noSpeechTimeoutMs = 2000;
        EndpointDetector d = new EndpointDetector(c);
        EndpointDetector.State st = EndpointDetector.State.WAITING;
        for (int i = 0; i < 60 && st == EndpointDetector.State.WAITING; i++) {
            st = d.onFrame(Pcm.frame(false), Pcm.FRAME_SAMPLES * 2, 0, false);
        }
        assertEquals(EndpointDetector.State.NO_SPEECH, st);
    }

    @Test
    void short_noise_burst_is_not_speech() {
        EndpointDetector d = new EndpointDetector(new EndpointDetector.Config());
        for (int i = 0; i < 10; i++) d.onFrame(Pcm.frame(false), Pcm.FRAME_SAMPLES * 2, 0, false);
        d.onFrame(Pcm.frame(true), Pcm.FRAME_SAMPLES * 2, 0, false);   // один «щелчок» 50 мс
        for (int i = 0; i < 30; i++) d.onFrame(Pcm.frame(false), Pcm.FRAME_SAMPLES * 2, 0, false);
        assertEquals(EndpointDetector.State.WAITING, d.state());
    }

    @Test
    void speech_resuming_after_soft_endpoint_cancels_it_but_a_single_click_does_not() {
        EndpointDetector d = new EndpointDetector(new EndpointDetector.Config());
        for (int i = 0; i < 4; i++) d.onFrame(Pcm.frame(false), Pcm.FRAME_SAMPLES * 2, 0, false);
        for (int i = 0; i < 30; i++) d.onFrame(Pcm.frame(true), Pcm.FRAME_SAMPLES * 2, 20, false);
        EndpointDetector.State st = EndpointDetector.State.SPEAKING;
        for (int i = 0; i < 20 && st != EndpointDetector.State.ENDPOINT; i++) {
            st = d.onFrame(Pcm.frame(false), Pcm.FRAME_SAMPLES * 2, 20, false);
        }
        assertEquals(EndpointDetector.State.ENDPOINT, st);

        // одиночный «щелчок» — не речь: конец ответа остаётся в силе
        assertEquals(EndpointDetector.State.ENDPOINT, d.onFrame(Pcm.frame(true), Pcm.FRAME_SAMPLES * 2, 20, false));
        assertEquals(EndpointDetector.State.ENDPOINT, d.onFrame(Pcm.frame(false), Pcm.FRAME_SAMPLES * 2, 20, false));

        // три голосовых кадра подряд — человек продолжил
        d.onFrame(Pcm.frame(true), Pcm.FRAME_SAMPLES * 2, 20, false);
        d.onFrame(Pcm.frame(true), Pcm.FRAME_SAMPLES * 2, 20, false);
        assertEquals(EndpointDetector.State.SPEAKING, d.onFrame(Pcm.frame(true), Pcm.FRAME_SAMPLES * 2, 20, false));
        assertEquals(0, d.silenceMs());
    }
}
