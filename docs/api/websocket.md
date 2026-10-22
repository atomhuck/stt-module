# WebSocket-протоколы Vosk и интервью

Оба канала предоставляет Spring Boot на порту 8080. Это WebSocket, а не HTTP-маршруты OpenAPI. Бинарное тело — сырой PCM16 little-endian, 16 кГц, моно, без WAV-заголовка. Серверный буфер бинарного сообщения ограничен 64 КиБ, текстового — 8 КиБ. Клиент общего демо отправляет аудио кандидата в `/ws/interview` кадрами по 50 мс; весь смешанный звук — в `/ws/stt`.

## `ws://127.0.0.1:8080/ws/stt`

1. Откройте соединение и отправляйте бинарные кадры PCM.
2. Сервер отправляет JSON `{"type":"partial","text":"...","words":[]}` при новой гипотезе и `{"type":"final","text":"...","words":[...]}` после паузы. Повторяющиеся partial не отправляются. Элемент `words` содержит `word`, `start`, `end` (секунды) и `confidence` (число либо `null`).
3. Отправьте текст `eof`; сервер дораспознает остаток, отправит последний `final` и закроет соединение обычным кодом.

Другое текстовое сообщение обработчик игнорирует. Обработчик ошибки транспорта пишет лог; отдельное JSON-сообщение об ошибке для этого канала не предусмотрено.

## `ws://127.0.0.1:8080/ws/interview`

После открытия отправьте `{"type":"start","levels":["Junior"],"candidate":"Иван"}`. `levels` принимает русские или английские названия уровней: Стажёр/Intern, Junior, Middle, Senior; пустой список используется общим демо для всех уровней. Повторный `start` на том же соединении игнорируется. Сервер посылает первый `question` и `service_status`.

| Клиентское текстовое сообщение | Назначение |
| --- | --- |
| `ready` | Вопрос показан/озвучен; начинается отсчёт ответа. |
| `peek` с `text` и `requestId` | Получить предварительную оценку без фиксации ответа. |
| `tts_end` с `afterMs` | Сообщить окончание TTS и дополнительную задержку перед слушанием. |
| `skip` | Пропустить текущий вопрос. |
| `stop` | Завершить интервью и запросить отчёт. |

Текстовые сообщения имеют JSON-форму `{"type":"ready"}`. Во время ответа посылайте бинарные PCM-кадры. До `start` такие кадры игнорируются. Неизвестный `type` после старта игнорируется. Ошибка разбора JSON может завершить обработку сообщения; `RuntimeException` внутри действия возвращается как `{"type":"error","message":"..."}`.

| Сообщение сервера | Основные поля и смысл |
| --- | --- |
| `question` | `number`, `total`, `level`, `topic`, `text`, `spoken`, `followUp`, `followUpNumber`, `graceMs`. Следующий вопрос показывают после `graceMs`. |
| `service_status` | `whisper=disabled|configured`, `whisperUrl`; configured не означает, что сервер Whisper уже доступен. |
| `partial` | `text` — изменяемый текст Vosk. |
| `preview` | `requestId`, `action`, `reason`, `coveragePercent`, `answerWords`, `recognized`, `suggestedFollowUp`, `readOnly=true`, `computeMs`. Это только подсказка. |
| `decision` | `action=ASK_FOLLOW_UP|NEXT_QUESTION|FINISH`, `reason`, `coveragePercent`, `answerWords`, `recognized`, `timing`. Решение может быть отменено в окне `graceMs`. |
| `retract` | `reason=speech_resumed`, возможно `question` с восстановленным текущим вопросом. |
| `refined` | Уточнённый `text` и, при детальном Whisper, `words`, `trimStartMs`, `answerId`. При текстовом fallback слова отсутствуют. |
| `whisper_error` | `answerId`, `message`; основной анализ при этом сохраняется. |
| `warning` | `code=asr_slow`, `rtf`, `message`; Vosk может отставать от аудио. |
| `finalizing` | Ожидание асинхронного Whisper перед отчётом. |
| `report` | `report` с оценками, необязательные `excel.file/path/url` либо `excelError`. Эталонные ответы в этом сообщении не передаются, но XLSX может их содержать. |
| `error` | `message` об ошибке обработки действия. |

Последовательность: `start → question → ready → PCM → partial/preview → decision → [retract либо question]` повторяется до `stop` или окончания банка; далее возможны `finalizing`, `refined` и `report`. При `skip` сервер переходит по правилам движка. В общем демо `IntegrationBridge` самостоятельно отправляет `start`, `ready`, `peek`, `skip` и `stop`; браузер обращается к локальному HTTP-серверу `:8765`.

Пример начала отдельного интервью:

```json
{"type":"start","levels":["Junior"],"candidate":"Иван"}
```

После получения `question`:

```json
{"type":"ready"}
```

При разрыве соединения сервер закрывает связанную с ним речевую сессию. Для нового интервью откройте новое соединение. Форматы и состояния сверяйте с `InterviewWebSocketHandler`, `LiveInterview` и `SttWebSocketHandler`.
