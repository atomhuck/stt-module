# Разработка и проверка

## Локальная проверка

Из корня репозитория:

```powershell
mvn -f server-prototype/java/pom.xml test
mvn -f server-prototype/java/pom.xml package -DskipTests
mvn -f server-prototype/java/pom.xml javadoc:javadoc
node --test server-prototype/java/src/test/js/audio-worklet.test.cjs server-prototype/java/src/test/js/integrated-worklet.test.cjs server-prototype/java/src/test/js/integrated-page.test.cjs
mvn -f vosk-stt-demo/pom.xml test
mvn -f vosk-stt-demo/pom.xml package -DskipTests
mvn -f vosk-stt-demo/pom.xml javadoc:javadoc
node --test vosk-stt-demo/src/test/js/interview-client-test.js
python -m unittest discover -s vosk-stt-demo/tools -p 'test_*.py'
npx --yes @redocly/cli@2.64.1 lint docs/api/openapi.yaml
npx --yes @redocly/cli@2.64.1 lint docs/api/interview-openapi.yaml
npx --yes @redocly/cli@2.64.1 lint docs/api/whisper-openapi.yaml
```

Javadoc появляется в `target/reports/apidocs` каждого Maven-модуля. Тест с реальной моделью Vosk пропускается, когда модель не установлена. CI выполняет эти команды без больших моделей. Локальный HTTP Whisper можно проверить с `--backend fake`; живой прогон с моделями — отдельно по [сценарию](../vosk-stt-demo/demo/SCENARIO.md).

## При изменении функциональности

- Изменили CLI-режим или параметр — обновите [справочник](cli.md) и работающий пример.
- Изменили HTTP-маршрут, заголовок, состояние или ошибку — обновите соответствующий [OpenAPI-контракт](api/README.md) в том же pull request.
- Изменили WebSocket-сообщение — обновите [справочник](api/websocket.md) и примеры; изменили JSON/CSV/XLSX — обновите [форматы](output-formats.md).
- Изменили настройку модели, интервью или Whisper — обновите [инструкцию сервиса](vosk-interview.md); при изменении инженерного решения добавьте ADR.
- Изменили неочевидную логику — проверьте русский Javadoc и комментарии рядом с ней. Не добавляйте комментарии, которые только пересказывают код.

## Типичные ошибки

| Симптом | Проверка |
| --- | --- |
| Не найдена GGUF/ONNX-модель или DLL | Проверьте `--nemo-model`, `--speaker-model`, `--nemo-lib` и архитектуру Windows x64. |
| Нативная ошибка `msvcp140.dll` | Сверьте JDK и DLL Nemotron; для описанной конфигурации JDK 23 используйте [подготовку SDK](../server-prototype/java/README.md#требования). |
| Неверный WAV | Нужен несжатый PCM16 16 кГц; `Support.readWav` сводит каналы в моно. |
| HTTP 403 или 409 | Проверьте `Origin`, `X-Session-Id`, порядок `X-Sequence` и текущую `phase` в `/api/status`. |
| Нет Vosk/Whisper в `integrated` | Запускайте `DemoLauncher` и проверьте `vosk-stt-demo` по [инструкции](../server-prototype/java/INTEGRATED_DEMO.md#если-запуск-не-проходит). |
| `finished: false` | Откройте `capture.stop_reason` и `capture.error` в `summary.json`. |
