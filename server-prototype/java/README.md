# Java worker диаризации

Java-перенос `stream_replay.py` и `verify_candidate.py`. Используются те же локальные модели Nemotron 3 Diarization (GGUF) и 3D-Speaker CAM++ (ONNX). Немотрон вызывается через его C API и JNA, эмбеддинги — через Java API sherpa-onnx. Весь анализ выполняется на CPU сервера. Файлы моделей и аудио в Git не входят; worker их не скачивает.

## Что получается на выходе

- `stream`: `events.jsonl` (строка JSON на каждый буфер), `segments.json` (завершённые реплики), `summary.json` (время и счётчики). Названия полей и структура сохранены от Python-скрипта. Показатели времени отражают конкретный запуск и не обязаны совпадать по числам.
- `verify`: `*.verified.json` и `*.verified.csv` с теми же полями, порядком колонок, UTF-8 BOM и переводами строк, что у Python-скрипта.

Обе модели загружаются по одному разу за прогон. В `stream` открывается одна сессия Nemotron; буферы по 0,64 с поступают в неё последовательно, а состояние говорящих сохраняется между буферами. Эталон кандидата заранее берётся из окна 20–30 с исходного файла. Это именно способ воспроизвести тест: в продукте эталон должен приходить от кнопки записи голоса кандидата. В этих результатах нет текста слов: STT не подключена.

## Требования

- Windows x64, JDK 17+ и Maven 3; здесь проверено с Java 23 и Maven 3.9.
- Локальные файлы `nemo_speech_asr_c.dll` и связанных DLL, GGUF Nemotron, ONNX CAM++ и WAV PCM16 16 кГц.
- Доступ Maven к Maven Central и JitPack при первой сборке Java-библиотек. Это зависимости сборки на машине разработчика или сервере, а не загрузка моделей на ноутбук интервьюера.

**Для установленного здесь JDK 23 нужен отдельный Java SDK.** В его `bin` лежит старый `msvcp140.dll` (14.36), а поставка Nemotron использует 14.44. Обычный запуск Java с этим JDK вызывает сбой нативной библиотеки. Скрипт ниже создаёт отдельную копию запускаемых файлов JDK с совместимыми DLL, сохраняя установленный JDK и модель без изменений:

```powershell
cd 'C:\Users\Ефим\OneDrive\Документы\ChatGPT\AI референт\stt-module\server-prototype\java'
.\prepare-windows-jdk.ps1 `
  -JdkHome 'C:\Program Files\Java\jdk-23' `
  -NemoBin 'C:\Users\Public\NemotronTest\runtime\bin' `
  -Target 'C:\Users\Public\NemotronTest\java-idea-sdk'
```

Если ваша установка JDK уже совместима с DLL Nemotron, используйте её напрямую. Указанная проблема относится к конкретным версиям установленного здесь JDK и нативной поставки.

## Запуск в IntelliJ IDEA с нуля

1. Откройте IntelliJ IDEA → **Open** → выберите `server-prototype/java/pom.xml` → **Open as Project**. Дождитесь **Load Maven Changes**. Если Maven не найден, укажите установленный Maven в **Settings → Build Tools → Maven**.
2. В **File → Project Structure → SDKs** добавьте SDK из `C:\Users\Public\NemotronTest\java-idea-sdk`, затем выберите его как **Project SDK**. Язык проекта — Java 17; запуск здесь проверен на подготовленном Java 23.
3. Откройте **Run → Edit Configurations → + → Application**. Main class: `ru.aireferent.diarization.Main`. Module classpath: `diarization-worker`. JRE: подготовленный SDK. Working directory: каталог `server-prototype/java`. Для корректного отображения русского текста в консоли можно добавить VM option `-Dfile.encoding=UTF-8`.
4. В **Program arguments** вставьте команду ниже целиком одной строкой (переносы здесь только для чтения):

```text
stream --audio "C:\Users\Ефим\OneDrive\Документы\ChatGPT\AI референт\diarization-test\ru_interview_dialogue_125s.wav" --nemo-lib "C:\Users\Public\NemotronTest\runtime\bin\nemo_speech_asr_c.dll" --nemo-model "C:\Users\Public\NemotronTest\model.gguf" --speaker-model "C:\Users\Public\NemotronTest\speaker_embedding_model.onnx" --output-dir "C:\Users\Public\NemotronTest\java-idea-run" --chunk-seconds 0.64 --pace realtime
```

5. Нажмите **Run**. Для быстрого прогона замените `--pace realtime` на `--pace fast`. Чтобы ограничить тест первыми секундами, добавьте, например, `--max-seconds 35`. Тест полного интервью состоит из 196 буферов. В консоли идут JSON-события; в выходном каталоге появятся три файла. Нативная библиотека sherpa-onnx может отдельно напечатать диагностическую строку в stderr при загрузке; формат `events.jsonl` от этого не меняется.

Для файловой проверки создайте вторую конфигурацию **Application** с тем же Main class и SDK, но с такими аргументами:

```text
verify --audio "C:\Users\Ефим\OneDrive\Документы\ChatGPT\AI референт\diarization-test\ru_interview_dialogue_125s.wav" --diarization-json "C:\Users\Public\NemotronTest\result_ru_dialogue_125s.json" --model "C:\Users\Public\NemotronTest\speaker_embedding_model.onnx" --output "C:\Users\Public\NemotronTest\java-idea-verify.json" --csv "C:\Users\Public\NemotronTest\java-idea-verify.csv"
```

Если JSON Nemotron ещё не создан, вместо `--diarization-json` передайте `--nemo-exe "...\nemo-speech.exe" --nemo-model "...\model.gguf"`. Для отдельного отладочного WAV эталона можно указать `--reference-wav PATH`.

## Запуск из терминала

В каталоге `server-prototype/java`:

```powershell
mvn package
& 'C:\Users\Public\NemotronTest\java-idea-sdk\bin\java.exe' -jar target\diarization-worker-1.0.0.jar stream `
  --audio 'C:\Users\Ефим\OneDrive\Документы\ChatGPT\AI референт\diarization-test\ru_interview_dialogue_125s.wav' `
  --nemo-lib 'C:\Users\Public\NemotronTest\runtime\bin\nemo_speech_asr_c.dll' `
  --nemo-model 'C:\Users\Public\NemotronTest\model.gguf' `
  --speaker-model 'C:\Users\Public\NemotronTest\speaker_embedding_model.onnx' `
  --output-dir 'C:\Users\Public\NemotronTest\java-idea-run' --pace fast
```

## Проверка совместимости

На том же русском WAV длительностью 125 с Java выдала 196 событий `chunk` и 22 итоговых сегмента. Для каждого из 22 сегментов совпали с Python границы, роль, speaker ID, сходства, уверенность и основание решения. Счётчики: 13 «Кандидат», 8 «Интервьюер 1», 1 эталон. В файловом режиме JSON и CSV при одинаковом входе совпали с Python **побайтно**. Значения задержек и времени загрузки в потоковом режиме зависят от конкретного запуска.
