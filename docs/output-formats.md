# Выходные файлы

Пути к файлам указываются в `summary.json` (`outputs`) или в консоли. `browser`, `integrated`, `live` и `stream` сохраняют три базовых файла:

| Файл | Содержание |
| --- | --- |
| `events.jsonl` | Одно JSON-событие на строку: запуск, обработанный чанк, остановка захвата, завершение и сводка. События `chunk` содержат время аудио, задержки, активных говорящих и новые завершённые сегменты. |
| `segments.json` | Массив завершённых сегментов, отсортированный по началу и концу. Обновляется по мере обработки в живых режимах. |
| `summary.json` | Параметры запуска, длительность, счётчики, задержки, причины остановки, `finished` и пути файлов. В живых режимах обновляется во время записи. |

Сегмент содержит `start`, `end`, `duration` (секунды), `label` (`Кандидат`, `Интервьюер N` или `Не определено`), `source_speaker_id`, `candidate_similarity`, `interviewer_similarity`, `confidence`, `confidence_percent` и `decision`. Значения сходства могут быть `null`, когда голосового эмбеддинга нет. Дополнительные поля, например `emitted_wall_s`, зависят от режима. `confidence_percent` — эвристика отображения, не калиброванная вероятность.

У `summary.json` поле `finished: false` означает неполное завершение, в частности при потере аудио или переполнении очереди. Для диагностики смотрите `capture.stop_reason`, `capture.error` и события `capture_stopped`. В `stream` набор метрик отличается от живых режимов; не переносите отсутствующие поля между ними.

`integrated` дополнительно пишет `stt-events.jsonl` (Vosk), `timeline-events.jsonl` (события ролей, подсказок, TTS и решений), `timeline.json` (слова с временем и статусом роли) и `integration-summary.json` (задержки и состояние мостов). Их назначение и метрики описаны в [инструкции демо](../server-prototype/java/INTEGRATED_DEMO.md#результаты-и-метрики).

`verify` сохраняет `*.verified.json` с параметрами проверки и событиями, а также `*.verified.csv`. Колонки CSV: `start`, `end`, `duration`, `label`, `candidate_similarity`, `interviewer_similarity`, `source_speaker_id`, `confidence`, `decision`. CSV записывается с UTF-8 BOM для табличных редакторов.
