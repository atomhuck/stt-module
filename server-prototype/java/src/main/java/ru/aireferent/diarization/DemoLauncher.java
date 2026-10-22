package ru.aireferent.diarization;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Запускает локальные сервисы и страницу интегрированного демо из одной конфигурации.
 *
 * @version 1.0.0
 */
public final class DemoLauncher {
    private static final List<Process> CHILDREN = new ArrayList<>();

    private DemoLauncher() {}

    /**
     * Проверяет зависимости и запускает Vosk, Whisper и браузерный worker.
     *
     * @param args аргументы запуска worker
     * @throws Exception если подготовка или запуск сервиса завершились ошибкой
     */
    public static void main(String[] args) throws Exception {
        Path javaModule = Path.of("").toAbsolutePath().normalize();
        Path repository = javaModule.getParent().getParent();
        Path artem = envPath("ARTEM_PROJECT", repository.resolve("vosk-stt-demo"));
        Path publicDir = envPath("NEMOTRON_HOME", Path.of("C:/Users/Public/NemotronTest"));
        Path jar = artem.resolve("target/vosk-stt-demo-1.0.0.jar");
        Path python = envPath("WHISPER_PYTHON", publicDir.resolve("whisper-venv/Scripts/python.exe"));
        Path whisper = artem.resolve("tools/whisper_server.py");
        Path model = envPath("VOSK_MODEL_PATH", publicDir.resolve("vosk-model-ru-0.42-native"));
        Path questions = artem.resolve("demo/questions-six.tsv");
        Path dll = publicDir.resolve("runtime/bin/nemo_speech_asr_c.dll");
        Path gguf = publicDir.resolve("model.gguf");
        Path speaker = publicDir.resolve("speaker_embedding_model.onnx");
        for (Path path : List.of(jar, python, whisper, model.resolve("README"),
                model.resolve("am/final.mdl"), model.resolve("graph/words.txt"),
                questions, dll, gguf, speaker)) {
            if (!Files.exists(path)) throw new IllegalStateException("Не найдено: " + path);
        }
        String modelReadme = Files.readString(model.resolve("README"), StandardCharsets.UTF_8);
        if (!modelReadme.contains("version 0.42"))
            throw new IllegalStateException("Модель по VOSK_MODEL_PATH не подтверждает версию 0.42: " + model);
        for (int port : List.of(8080, 9000, 8765)) ensurePortFree(port);
        Runtime.getRuntime().addShutdownHook(new Thread(DemoLauncher::stopChildren, "demo-shutdown"));
        try {
            ProcessBuilder artemCommand = new ProcessBuilder(
                    Path.of(System.getProperty("java.home"), "bin", "java.exe").toString(),
                    "-Xmx512m", "-Dfile.encoding=UTF-8", "-jar", jar.toString(),
                    "--interview.questions-path=" + questions);
            artemCommand.directory(artem.toFile());
            artemCommand.environment().put("VOSK_MODEL_PATH", model.toString());
            artemCommand.environment().put("WHISPER_URL", "http://127.0.0.1:9000/transcribe");
            Process artemProcess = start("Vosk", artemCommand);
            awaitService("Vosk", "http://127.0.0.1:8080/", artemProcess, Duration.ofMinutes(6));

            ProcessBuilder whisperCommand = new ProcessBuilder(python.toString(), whisper.toString(),
                    "--model", System.getenv().getOrDefault("WHISPER_MODEL", "small"),
                    "--device", "cpu", "--compute-type", "int8", "--cpu-threads", "2");
            whisperCommand.directory(artem.toFile());
            whisperCommand.environment().put("HF_HOME", publicDir.resolve("whisper-cache").toString());
            whisperCommand.environment().put("HF_HUB_DISABLE_XET", "1");
            whisperCommand.environment().put("PYTHONIOENCODING", "utf-8");
            Process whisperProcess = start("Whisper", whisperCommand);
            awaitService("Whisper", "http://127.0.0.1:9000/", whisperProcess, Duration.ofMinutes(10));

            System.out.println("Vosk 0.42 и Whisper готовы. Откройте http://localhost:8765 в Chrome или Edge.");
            Main.main(new String[]{"integrated", "--nemo-lib", dll.toString(),
                    "--nemo-model", gguf.toString(), "--speaker-model", speaker.toString(),
                    "--output-dir", publicDir.resolve("integrated-runs").toString(),
                    "--artem-url", "ws://127.0.0.1:8080"});
            // BrowserMode возвращается сразу после запуска HTTP-сервера; дочерние
            // процессы должны жить до конца браузерного интервью.
            keepServicesAlive();
        } finally {
            stopChildren();
        }
    }

    private static Path envPath(String name, Path fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : Path.of(value).toAbsolutePath().normalize();
    }

    private static Process start(String name, ProcessBuilder builder) throws Exception {
        builder.redirectErrorStream(true);
        Process process = builder.start();
        CHILDREN.add(process);
        Thread output = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    process.getInputStream(), StandardCharsets.UTF_8))) {
                for (String line; (line = reader.readLine()) != null; )
                    System.out.println("[" + name + "] " + line);
            } catch (Exception error) {
                System.err.println("[" + name + "] Лог недоступен: " + error);
            }
        }, name + "-output");
        output.setDaemon(true);
        output.start();
        return process;
    }

    private static void ensurePortFree(int port) throws Exception {
        try (ServerSocket socket = new ServerSocket()) {
            socket.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port));
        } catch (Exception error) {
            throw new IllegalStateException("Порт " + port + " уже занят. Остановите прежний запуск демо в IntelliJ.", error);
        }
    }

    private static void awaitService(String name, String url, Process process, Duration timeout) throws Exception {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(2)).GET().build();
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (!process.isAlive()) throw new IllegalStateException(name + " завершился до готовности, код "
                    + process.exitValue() + ". Смотрите строки [" + name + "] выше.");
            try {
                HttpResponse<Void> response = http.send(request, HttpResponse.BodyHandlers.discarding());
                if (response.statusCode() < 500) return;
            } catch (Exception ignored) {
                // Сервис ещё загружает модель.
            }
            Thread.sleep(1000);
        }
        throw new IllegalStateException(name + " не запустился за " + timeout.toMinutes()
                + " мин. Смотрите строки [" + name + "] выше.");
    }

    private static void keepServicesAlive() throws InterruptedException {
        System.out.println("Сервисы Vosk и Whisper остаются запущенными, пока работает это окно IntelliJ.");
        while (!Thread.currentThread().isInterrupted()) {
            for (Process process : CHILDREN) {
                if (!process.isAlive()) {
                    System.err.println("Один из сервисов демо неожиданно завершился (код "
                            + process.exitValue() + "); остановлен весь запуск.");
                    System.exit(1);
                }
            }
            Thread.sleep(1000);
        }
    }

    private static synchronized void stopChildren() {
        for (Process process : CHILDREN) if (process.isAlive()) process.destroy();
    }
}
