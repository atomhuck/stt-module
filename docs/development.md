# Разработка и проверка

## Локальная проверка

Из каталога `server-prototype/java`:

```powershell
mvn test
mvn package
mvn javadoc:javadoc
node --test src/test/js/audio-worklet.test.cjs src/test/js/integrated-worklet.test.cjs src/test/js/integrated-page.test.cjs
```

Генерируемый Javadoc находится в `target/reports/apidocs`. OpenAPI проверяется из корня репозитория командой `npx --yes @redocly/cli@2.64.1 lint docs/api/openapi.yaml`; эти проверки выполняются и в CI. Сборка и тесты не загружают большие модели. Живой прогон с моделями проверяется отдельно по [сценарию демо](../server-prototype/java/INTEGRATED_DEMO.md).

## При изменении функциональности

- Изменили CLI-режим или параметр — обновите [справочник](cli.md) и работающий пример.
- Изменили HTTP-маршрут, заголовок, состояние или ошибку — обновите [OpenAPI](api/openapi.yaml) в том же pull request.
- Изменили JSON/CSV — обновите [форматы](output-formats.md); при изменении инженерного решения добавьте ADR.
- Изменили неочевидную логику — проверьте русский Javadoc и комментарии рядом с ней. Не добавляйте комментарии, которые только пересказывают код.

## Типичные ошибки

| Симптом | Проверка |
| --- | --- |
| Не найдена GGUF/ONNX-модель или DLL | Проверьте `--nemo-model`, `--speaker-model`, `--nemo-lib` и архитектуру Windows x64. |
| Нативная ошибка `msvcp140.dll` | Сверьте JDK и DLL Nemotron; для описанной конфигурации JDK 23 используйте [подготовку SDK](../server-prototype/java/README.md#требования). |
| Неверный WAV | Нужен несжатый PCM16 16 кГц; `Support.readWav` сводит каналы в моно. |
| HTTP 403 или 409 | Проверьте `Origin`, `X-Session-Id`, порядок `X-Sequence` и текущую `phase` в `/api/status`. |
| Нет Vosk/Whisper в `integrated` | Запускайте `DemoLauncher` и проверьте соседний сервис по [инструкции](../server-prototype/java/INTEGRATED_DEMO.md#если-запуск-не-проходит). |
| `finished: false` | Откройте `capture.stop_reason` и `capture.error` в `summary.json`. |
