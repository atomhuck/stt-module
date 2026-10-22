# Команды и настройка

## Предпосылки

Поддерживаемая конфигурация прототипа: Windows x64, JDK 17+, Maven 3, локальные `nemo_speech_asr_c.dll`, GGUF Nemotron 3 и ONNX CAM++. Для `stream` и `verify` нужен несжатый WAV PCM16 16 кГц; многоканальный WAV при чтении сводится в моно. Модели приложение не скачивает. Особенность сочетания JDK 23 и конкретных DLL на машине разработчика описана в [инструкции Java-модуля](../server-prototype/java/README.md#требования).

Из каталога `server-prototype/java` соберите JAR командой `mvn package`. Все команды ниже выполняются оттуда. Пути в примерах замените своими.

## `stream`

Последовательно подаёт WAV в одну сессию Nemotron; по умолчанию эталон кандидата берётся из интервала 20–30 с того же файла. Выберите интервал, где говорит только кандидат.

```powershell
java -jar target/diarization-worker-1.0.0.jar stream `
  --audio 'D:\audio\interview.wav' `
  --nemo-lib 'D:\models\runtime\bin\nemo_speech_asr_c.dll' `
  --nemo-model 'D:\models\model.gguf' `
  --speaker-model 'D:\models\speaker_embedding_model.onnx' `
  --output-dir 'D:\runs\stream' --pace fast
```

`--pace realtime|fast` задаёт темп; `--chunk-seconds` по умолчанию равен `0.64`; `--enroll-start` и `--enroll-duration` меняют окно эталона; `--max-seconds` ограничивает длину прогона. `--threshold` по умолчанию `0.60`, `--num-threads` — `4`, `--settle-seconds` — `0.64`. `--save-chunks` сохраняет отдельные WAV-чанки для диагностики.

## `browser` и `integrated`

Оба режима открывают локальную страницу на `http://localhost:8765` и принимают запись эталона и аудио через [HTTP API](api/openapi.yaml). Обязательны `--nemo-lib`, `--nemo-model`, `--speaker-model`, `--output-dir`; очередь по умолчанию вмещает 32 чанка. В `integrated` дополнительно используется `--artem-url` (по умолчанию `ws://127.0.0.1:8080`), а действия с вопросом, TTS и подсказками доступны только в этом режиме.

Для отдельной страницы диаризации замените `stream` из примера выше на `browser` и уберите `--audio` и `--pace`. Браузер откроется автоматически; если этого не произошло, откройте `http://localhost:8765` вручную. Разрешите микрофон и демонстрацию всего экрана со звуком.

Для полного демо запускайте `DemoLauncher` по [инструкции](../server-prototype/java/INTEGRATED_DEMO.md): он проверяет и запускает соседний сервис Артёма и Whisper. Прямой запуск `Main integrated` поднимет лишь Java-страницу и не запустит эти процессы.

## `live` и `live-devices`

`live-devices` выводит имена микрофонов. `live` записывает 10-секундный эталон кандидата, затем интервью из одного микрофона. Обязательны те же четыре пути, что у `browser`; `--mic-name` выбирает устройство. `--queue-chunks` по умолчанию `32`. При переполнении очереди захват останавливается, уже принятые чанки обрабатываются, а причина фиксируется в `summary.json`.

Для живой записи замените `stream` из примера выше на `live` и уберите `--audio` и `--pace`. Штатно завершите запись нажатием Enter или добавьте `--max-seconds 30`. Команда `java -jar target/diarization-worker-1.0.0.jar live-devices` показывает доступные устройства.

## `verify`

Сопоставляет сегменты готовой диаризации с эталоном и сохраняет `*.verified.json` и `*.verified.csv`:

```powershell
java -jar target/diarization-worker-1.0.0.jar verify `
  --audio 'D:\audio\interview.wav' `
  --diarization-json 'D:\runs\diarization.json' `
  --model 'D:\models\speaker_embedding_model.onnx' `
  --output 'D:\runs\interview.verified.json' `
  --csv 'D:\runs\interview.verified.csv'
```

Если готового JSON нет, передайте `--nemo-exe` и `--nemo-model`; режим временно вызовет Nemotron CLI. Для отдельного эталона используйте `--reference-wav`. Дополнительные параметры и пример IntelliJ IDEA есть в [инструкции Java-модуля](../server-prototype/java/README.md#запуск-в-intellij-idea-с-нуля).
