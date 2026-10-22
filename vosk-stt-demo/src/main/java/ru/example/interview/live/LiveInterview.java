package ru.example.interview.live;

import ru.example.interview.engine.AnswerTiming;
import ru.example.interview.engine.EndpointDetector;
import ru.example.interview.engine.InterviewDetails;
import ru.example.interview.engine.InterviewSession;
import ru.example.interview.engine.SpokenText;
import ru.example.interview.engine.TermNormalizer;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

/**
 * Склейка конвейера для одного подключения: звук → Vosk → конец ответа → решение → следующий вопрос.
 *
 * <h3>Синхронизация: не перебивать и не терять слова</h3>
 * Конец ответа проходит три стадии, и на каждой человек может продолжить говорить:
 * <ol>
 *   <li><b>Мягкий конец</b> (тишина ≈ 0,45 с): распознавание «дожимается» (flush), так что текст полный;
 *       по нему сразу считается <i>предварительное</i> решение — но не принимается.</li>
 *   <li><b>Жёсткий конец</b>: решение принимается после дополнительной паузы, зависящей от решения.
 *       Законченный ответ → почти сразу (+0,15 с, итого ≈ 0,6 с). Неполный ответ, после которого нужно уточнение, —
 *       намного дольше (+1,5 с): именно тут человек чаще всего просто думает, и перебивать его нельзя.
 *       Если речь возобновилась — предварительное решение отбрасывается, текст продолжает копиться.</li>
 *   <li><b>Окно отмены</b> (grace, ≈ 1 с после решения): микрофон остаётся открытым, а следующий вопрос
 *       клиент показывает и озвучивает только после окна. Если человек всё-таки продолжил — решение
 *       откатывается (retract), и он дозаканчивает ответ на прежний вопрос.</li>
 * </ol>
 * Решение считается за микросекунды; тяжёлое (flush Vosk, второй проход Whisper, пересчёт отчёта) идёт вне критического пути.
 * @version 1.0.0
 */
public final class LiveInterview {

    /**
     * Передаёт события интервью WebSocket-клиенту.
     *
     * @version 1.0.0
     */
    public interface Sender {
        void send(Map<String, Object> message);
    }

    /**
     * Паузы синхронизации, мс (сверх «мягкого конца» детектора).
     *
     * @param nextExtraMs      ответ достаточный → к следующему вопросу
     * @param otherNextExtraMs «не знаю», нет ответа, не по теме и т.п. → к следующему вопросу
     * @param followUpExtraMs  ответ неполный → уточняющий вопрос (самая осторожная пауза)
     * @param graceMs          окно отмены решения; следующий вопрос клиент показывает после него
     * @version 1.0.0
     */
    public record Timing(int nextExtraMs, int otherNextExtraMs, int followUpExtraMs, int graceMs) {
        /**
         * Выполняет операцию публичного контракта компонента.
         *
         * @return результат операции
         */
        public static Timing defaults() {
            return new Timing(150, 600, 1500, 1000);
        }
    }

    private enum Phase { IDLE, LISTENING, TENTATIVE, GRACE, WAIT_READY }

    private static final int BYTES_PER_MS = 32;                 // 16 кГц * 2 байта
    private static final int MAX_AUDIO_BYTES = 180_000 * BYTES_PER_MS;

    private final SpeechEngine speech;
    private final InterviewSession interview;
    private final EndpointDetector detector;
    private final Sender out;
    private final TermNormalizer normalizer;
    private final SpokenText spoken;

    private Timing timing = Timing.defaults();
    private Transcriber transcriber;
    private long pendingWaitMs = 25_000;
    private ReportExporter exporter;
    private String candidate = "";
    private java.time.Instant startedAt = java.time.Instant.now();
    private Map<String, String> settings = Map.of();
    private final List<CompletableFuture<?>> pending = new CopyOnWriteArrayList<>();

    private final StringBuilder committed = new StringBuilder();
    private final ByteArrayOutputStream answerAudio = new ByteArrayOutputStream();
    private String partial = "";
    private String current = "";          // для анализа
    private String currentDisplay = "";   // для экрана
    private int words;
    private boolean connector;

    private Phase phase = Phase.IDLE;
    private InterviewSession.Peek tentative;
    private long commitAtSilenceMs;
    private long graceStartAudioMs;
    private boolean pendingFinish;
    private long ttsEndMs;               // когда закончилась озвучка вопроса (режим «в наушниках»): это время не считается раздумьем
    private volatile int answerVersion;   // растёт при откате: устаревшие результаты Whisper игнорируются
    private int answerId;
    private boolean finished;

    // контроль «успевает ли распознавание за речью»
    private double rtf;
    private int frames;
    private long lastRtfWarn;

    /**
     * Создаёт объект с указанными зависимостями и настройками.
     *
     * @param speech речевой движок
     * @param interview состояние интервью
     * @param detector детектор конца ответа
     * @param out выходной поток
     */
    public LiveInterview(SpeechEngine speech, InterviewSession interview, EndpointDetector detector, Sender out) {
        this.speech = speech;
        this.interview = interview;
        this.detector = detector;
        this.out = out;
        this.normalizer = interview.knowledge().normalizer();
        this.spoken = interview.knowledge().spoken();
    }

    /**
     * Настраивает представление отчёта перед записью XLSX.
     *
     * @param timing паузы между состояниями интервью
     */
    public void setTiming(Timing timing) {
        this.timing = timing;
    }

     * @param transcriber параметр операции
     * @param waitMs параметр операции
    /** Необязательный второй проход распознавания (Whisper). */
    public void setTranscriber(Transcriber transcriber, long waitMs) {
        this.transcriber = transcriber;
        this.pendingWaitMs = waitMs;
    }

     * @param exporter экспортёр отчёта
     * @param candidate имя кандидата
     * @param settings настройки для листа методики
    /** Куда и от чьего имени сохранять итоговый отчёт (Excel). */
    public void setExport(ReportExporter exporter, String candidate, Map<String, String> settings) {
        this.exporter = exporter;
        this.candidate = candidate == null ? "" : candidate.trim();
        this.settings = settings == null ? Map.of() : settings;
    }

    /**
     * Отправляет первый вопрос и начинает состояние интервью.
     */
    public void start() {
        startedAt = java.time.Instant.now();
        out.send(question(interview.start(), 0));
    }

    /** Клиент показал/озвучил вопрос и готов слушать ответ: с этого момента идёт отсчёт «скорости ответа». */
    public void onReady() {
        if (finished) return;
        committed.setLength(0);
        answerAudio.reset();
        partial = "";
        current = "";
        currentDisplay = "";
        words = 0;
        connector = false;
        tentative = null;
        pendingFinish = false;
        ttsEndMs = 0;
        detector.reset();
        phase = Phase.LISTENING;
    }

     * @param displayText текст для показа пользователю
     * @param requestId идентификатор предварительного запроса
    /** Read-only preview for the combined demo. It uses the same policy/analyzer without advancing the interview. */
    public void onPeek(String displayText, String requestId) {
        if (finished || phase == Phase.IDLE || phase == Phase.WAIT_READY || displayText == null || displayText.isBlank())
            return;
        long started = System.nanoTime();
        InterviewSession.Peek peek = interview.peek(normalizer.normalize(displayText, false));
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("type", "preview");
        event.put("requestId", requestId);
        event.put("action", peek.followUp() ? "ASK_FOLLOW_UP" : "NEXT_QUESTION");
        event.put("reason", peek.reason());
        event.put("coveragePercent", (int) Math.round(peek.coverage() * 100));
        event.put("answerWords", peek.words());
        event.put("recognized", displayText);
        event.put("suggestedFollowUp", peek.followUpQuestion());
        event.put("readOnly", true);
        event.put("computeMs", Math.round((System.nanoTime() - started) / 100_000.0) / 10.0);
        out.send(event);
    }

    /**
     * Режим «в наушниках»: микрофон открыт уже во время озвучки вопроса, и клиент сообщает, когда она закончилась
     * (мс после «готов»). Время озвучки не считается задержкой ответа: скорость оценивается от конца вопроса.
     * @param afterReadyMs параметр операции
     */
    public void onTtsEnd(long afterReadyMs) {
        ttsEndMs = Math.max(0, afterReadyMs);
    }

    /**
     * Обрабатывает PCM-кадр ответа и переходы после паузы.
     *
     * @param pcm байты PCM16 без WAV-заголовка
     * @param len число значимых байтов
     */
    public void onAudio(byte[] pcm, int len) {
        if (finished || phase == Phase.IDLE || phase == Phase.WAIT_READY) return;
        if (transcriber != null && answerAudio.size() < MAX_AUDIO_BYTES) answerAudio.write(pcm, 0, len);

        long t0 = System.nanoTime();
        SpeechEngine.Result r = speech.accept(pcm, len);
        trackRealtime(System.nanoTime() - t0, len);
        if (r != null) {
            if (r.isFinal()) {
                appendCommitted(r.text());
                partial = "";
            } else {
                partial = r.text();
            }
            refreshCurrent();
            send("partial", "text", currentDisplay);
        }

        EndpointDetector.State st = detector.onFrame(pcm, len, words, connector);
        switch (phase) {
            case LISTENING -> {
                if (st == EndpointDetector.State.ENDPOINT) enterTentative();
                else if (st == EndpointDetector.State.NO_SPEECH) commit("", "", new AnswerTiming(detector.audioMs(), 0), false);
            }
            case TENTATIVE -> {
                if (st != EndpointDetector.State.ENDPOINT) cancelTentative();                 // человек продолжил
                else if (detector.silenceMs() >= commitAtSilenceMs) commitCurrent(true);       // жёсткий конец
            }
            case GRACE -> {
                if (st == EndpointDetector.State.SPEAKING) retract();                          // продолжил в окне отмены
                else if (detector.audioMs() - graceStartAudioMs >= timing.graceMs()) endGrace();
            }
            default -> { }
        }
    }

    /** Кнопка «Пропустить вопрос». */
    public void onSkip() {
        if (finished || phase == Phase.GRACE) return;   // в окне отмены на экране ещё прежний вопрос
        commit("", "", new AnswerTiming(0, 0), false);
    }

    /** Кнопка «Завершить»: отчёт по уже отвеченным вопросам. */
    public void onStop() {
        if (finished) return;
        finishInterview();
    }

    // ------------------------------------------------------------------ стадии конца ответа

    /** Мягкий конец: дожимаем распознавание, чтобы текст был полным, и готовим (не принимаем!) решение. */
    private void enterTentative() {
        SpeechEngine.Result fin = speech.finish();
        if (fin != null) appendCommitted(fin.text());
        partial = "";
        refreshCurrent();
        send("partial", "text", currentDisplay);          // человек видит полный текст

        tentative = interview.peek(current);
        int extra = tentative.followUp() ? timing.followUpExtraMs()
                : "sufficient".equals(tentative.reason()) ? timing.nextExtraMs() : timing.otherNextExtraMs();
        commitAtSilenceMs = detector.silenceMs() + extra;
        phase = Phase.TENTATIVE;
        if (extra <= 0) commitCurrent(true);
    }

    private void cancelTentative() {
        tentative = null;
        phase = Phase.LISTENING;
    }

    private void commitCurrent(boolean grace) {
        commit(current, currentDisplay,
                new AnswerTiming(Math.max(0, detector.speechStartMs() - ttsEndMs), detector.speechDurationMs()), grace);
    }

    /** Жёсткий конец: решение принято и отправлено. Дальше — окно отмены. */
    private void commit(String text, String display, AnswerTiming at, boolean grace) {
        long t0 = System.nanoTime();
        InterviewSession.Step step = interview.submit(text, display, at);   // ← горячий путь
        long micros = (System.nanoTime() - t0) / 1000;
        InterviewSession.AttemptRef ref = interview.lastAttempt();
        tentative = null;

        Map<String, Object> decision = new LinkedHashMap<>();
        decision.put("type", "decision");
        decision.put("action", step.action().name());
        decision.put("reason", step.reason());
        decision.put("coveragePercent", (int) Math.round(step.coverage() * 100));
        decision.put("answerWords", step.analysis().words());
        decision.put("recognized", display);
        decision.put("answerId", ++answerId);
        Map<String, Object> timingInfo = new LinkedHashMap<>();
        timingInfo.put("silenceMs", detector.silenceMs());           // сколько ждали тишины до принятия решения
        timingInfo.put("decisionMicros", micros);                    // сколько заняло само решение
        timingInfo.put("serverMs", Math.round((detector.silenceMs() + micros / 1000.0) * 10) / 10.0);
        timingInfo.put("asrRealtimeFactor", Math.round(rtf * 100) / 100.0);
        timingInfo.put("speechStartMs", detector.speechStartMs());
        timingInfo.put("audioMs", detector.audioMs());
        decision.put("timing", timingInfo);
        out.send(decision);

        boolean finish = step.action() == InterviewSession.Action.FINISH;
        boolean withGrace = grace && timing.graceMs() > 0;
        if (finish) pendingFinish = true;
        else out.send(question(step.next(), withGrace ? timing.graceMs() : 0));

        // ---- вне критического пути ----
        // текст получен после flush Vosk, то есть уже финальный: фиксируем как «Vosk», остальное доточит Whisper
        interview.refine(ref, text, display, 1);
        if (transcriber != null && !text.isBlank())
            startSecondPass(ref, trimAnswerAudio(detector.speechStartMs()),
                    Math.max(0, detector.speechStartMs() - 300), answerId);

        if (withGrace) {
            phase = Phase.GRACE;
            graceStartAudioMs = detector.audioMs();
        } else {
            phase = Phase.WAIT_READY;
            if (finish) finishInterview();
        }
    }

    private void endGrace() {
        phase = Phase.WAIT_READY;
        if (pendingFinish) finishInterview();
    }

    /** Человек продолжил отвечать в окне отмены: откатываем решение, он договаривает на прежний вопрос. */
    private void retract() {
        answerVersion++;                                   // устаревший результат Whisper игнорируем
        InterviewSession.Prompt p = interview.undoLast();
        pendingFinish = false;
        phase = Phase.LISTENING;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "retract");
        m.put("reason", "speech_resumed");
        if (p != null) m.put("question", question(p, 0));
        out.send(m);
    }

    private void finishInterview() {
        finished = true;
        phase = Phase.IDLE;
        sendReport();
    }

    // ------------------------------------------------------------------ вспомогательное

    private void appendCommitted(String text) {
        if (text == null || text.isBlank()) return;
        if (committed.length() > 0) committed.append(' ');
        committed.append(text);
    }

    /** Следим, успевает ли распознавание за речью: если нет, текст отстаёт, и решения принимаются по неполному тексту. */
    private void trackRealtime(long asrNanos, int len) {
        double frameMs = len / (double) BYTES_PER_MS;
        double ratio = (asrNanos / 1e6) / frameMs;
        rtf = frames == 0 ? ratio : 0.9 * rtf + 0.1 * ratio;
        frames++;
        long now = System.currentTimeMillis();
        if (frames >= 20 && rtf > 0.85 && now - lastRtfWarn > 30_000) {
            lastRtfWarn = now;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("type", "warning");
            m.put("code", "asr_slow");
            m.put("rtf", Math.round(rtf * 100) / 100.0);
            m.put("message", "Распознавание не успевает за речью (обработка занимает " + Math.round(rtf * 100)
                    + " % времени звука): слова будут теряться или опаздывать. Возьмите лёгкую модель "
                    + "(vosk-model-small-ru-0.22), закройте тяжёлые программы или уменьшите нагрузку на процессор.");
            out.send(m);
        }
    }

    /** Берём аудио ответа: от начала речи (с запасом 300 мс) до конца. */
    private byte[] trimAnswerAudio(long speechStartMs) {
        byte[] all = answerAudio.toByteArray();
        int from = (int) Math.max(0, (speechStartMs - 300) * BYTES_PER_MS);
        from -= from % 2;
        if (from >= all.length || all.length - from < 1000 * BYTES_PER_MS / 2) return null;   // короче 0.5 с — не стоит
        return Arrays.copyOfRange(all, from, all.length);
    }

    private void startSecondPass(InterviewSession.AttemptRef ref, byte[] audio, long trimStartMs, int answerId) {
        if (audio == null) return;
        final int version = answerVersion;
        CompletableFuture<Void> f;
        if (transcriber instanceof WhisperHttpTranscriber whisper) {
            f = whisper.transcribeDetailed(audio).thenAccept(result -> {
                if (version != answerVersion) return;
                if (result.text().isBlank()) {
                    out.send(Map.of("type", "whisper_error", "answerId", answerId,
                            "message", "Whisper вернул пустую расшифровку"));
                    return;
                }
                interview.refine(ref, normalizer.normalize(result.text(), false),
                        normalizer.normalize(result.text(), true), 2);
                Map<String, Object> event = new LinkedHashMap<>();
                event.put("type", "refined");
                event.put("text", normalizer.normalize(result.text(), true));
                event.put("words", result.words());
                event.put("trimStartMs", trimStartMs);
                event.put("answerId", answerId);
                out.send(event);
            }).exceptionally(e -> {
                out.send(Map.of("type", "whisper_error", "answerId", answerId,
                        "message", String.valueOf(e.getCause() == null ? e : e.getCause())));
                return null;
            });
        } else {
            f = transcriber.transcribe(audio).thenAccept(text -> {
                if (text == null || text.isBlank() || version != answerVersion) return;
                interview.refine(ref, normalizer.normalize(text, false), normalizer.normalize(text, true), 2);
                send("refined", "text", normalizer.normalize(text, true));
            }).exceptionally(e -> {
                out.send(Map.of("type", "whisper_error", "answerId", answerId,
                        "message", String.valueOf(e.getCause() == null ? e : e.getCause())));
                return null;
            });
        }
        pending.add(f);
    }

    private void refreshCurrent() {
        String raw;
        if (committed.length() == 0) {
            raw = partial;
        } else {
            raw = partial.isBlank() ? committed.toString() : committed + " " + partial;
        }
        current = normalizer.normalize(raw, false);
        currentDisplay = normalizer.normalize(raw, true);
        words = Connectors.countWords(current);
        connector = Connectors.endsWithConnector(current);
    }

    private void sendReport() {
        if (!pending.isEmpty()) {
            send("finalizing", "message", "Уточняю распознанный текст…");
            try {
                CompletableFuture.allOf(pending.toArray(new CompletableFuture<?>[0])).get(pendingWaitMs, TimeUnit.MILLISECONDS);
            } catch (Exception ignored) {
                // не дождались Whisper — считаем по тому, что есть
            }
        }
        InterviewDetails details = interview.details(candidate, startedAt, settings);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "report");
        m.put("report", details.report());      // эталонные ответы в браузер не уходят — только оценки
        if (exporter != null) {
            try {
                ReportExporter.Result r = exporter.export(details);
                Map<String, Object> excel = new LinkedHashMap<>();
                excel.put("file", r.fileName());
                excel.put("path", r.path());
                excel.put("url", r.downloadUrl());
                m.put("excel", excel);
            } catch (Exception e) {
                m.put("excelError", String.valueOf(e.getMessage()));
            }
        }
        out.send(m);
    }

    private void send(String type, String key, Object value) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", type);
        m.put(key, value);
        out.send(m);
    }

    private Map<String, Object> question(InterviewSession.Prompt p, int graceMs) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "question");
        m.put("number", p.number());
        m.put("total", p.total());
        m.put("level", p.level());
        m.put("topic", p.topic());
        m.put("text", p.text());
        m.put("spoken", spoken.speak(p.text()));      // что реально читать вслух: «HashMap» → «хэшмэп»
        m.put("followUp", p.followUp());
        m.put("followUpNumber", p.followUpNumber());
        m.put("graceMs", graceMs);                    // клиент показывает и озвучивает вопрос только после этого окна
        return m;
    }
}
