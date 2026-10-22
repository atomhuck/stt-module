# Java worker диаризации

Java worker использует локальные Nemotron 3 (GGUF) и CAM++ (ONNX) на CPU. Эта инструкция содержит подробную настройку IntelliJ IDEA и командной строки для конкретной Windows-конфигурации. Общие режимы и параметры приведены в [справочнике CLI](../../docs/cli.md), форматы результатов — в [отдельном разделе](../../docs/output-formats.md), а принципы назначения ролей и границы интеграции — в [архитектуре](../../docs/architecture/README.md).

Для общего интервью с Vosk 0.42, TTS, подсказками и Whisper следуйте [инструкции интегрированного демо](INTEGRATED_DEMO.md) и запускайте `DemoLauncher`. Режим `browser` обслуживает только диаризацию без STT. `live` записывает микрофон, `stream` воспроизводит WAV, `verify` проверяет готовую диаризацию. Все модели, аудио и результаты остаются локально.

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

**Проверка JRE в конфигурации.** Первая строка окна Run должна начинаться с `"C:\Users\Public\NemotronTest\java-idea-sdk\bin\java.exe"`. Если там `"C:\Program Files\Java\jdk-23\bin\java.exe"`, откройте **Run → Edit Configurations → diarization-worker**, нажмите **Modify options → Add JRE** (если поле скрыто) и в поле **JRE** выберите подготовленный `java-idea-sdk`. Одной настройки Project SDK может быть недостаточно, если конфигурация запуска использует другой JRE. При несовместимой версии `msvcp140.dll` worker теперь сообщает об этом до нативного вызова.

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

На русском WAV длительностью 125 с ранний контрольный прогон Java выдал 196 событий `chunk` и 22 сегмента: 13 «Кандидат», 8 «Интервьюер 1», 1 эталон. Это исторический результат до последних изменений правил назначения ролей и добавления `confidence_percent`; он не описывает текущую точность или точные результаты на новом прогоне. Значения задержек и времени загрузки зависят от конкретного запуска.
