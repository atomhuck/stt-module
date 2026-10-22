# Локальный HTTP API

Полный контракт запросов, ответов и ошибок — [OpenAPI 3.1](openapi.yaml). Сервер доступен только на `127.0.0.1:8765` после запуска `browser` или `integrated`. `X-Session-Id` связывает действия с текущим интервью; это не средство сетевой авторизации.

## Порядок вызовов

1. `POST /api/enrollment/start` с пустым телом возвращает `session_id` и `phase=enrolling`.
2. Отправляйте сырой моно PCM16 little-endian 16 кГц в `POST /api/enrollment/audio`. Каждый запрос содержит `X-Session-Id` и последовательный `X-Sequence`, начиная с 0. WAV-заголовок в теле не нужен.
3. `POST /api/enrollment/finish` строит эталон. При `phase=retry` начните запись заново; при `phase=live` можно отправлять интервью.
4. Отправляйте аудио в `POST /api/audio` с новым счётчиком `X-Sequence`, снова начиная с 0. В `integrated` после заданного вопроса вызовите `POST /api/question/asked`.
5. Вызовите `POST /api/finish`, затем опрашивайте `GET /api/status` до `finished` или `error`. Ответ завершения может содержать промежуточный `phase=stopping`.

Пример отправки одного готового PCM-кадра в PowerShell; идентификатор получите из первого ответа:

```powershell
$sessionId = (Invoke-RestMethod -Method Post -Uri 'http://127.0.0.1:8765/api/enrollment/start').session_id
curl.exe -X POST 'http://127.0.0.1:8765/api/enrollment/audio' -H "X-Session-Id: $sessionId" -H 'X-Sequence: 0' -H 'Content-Type: application/octet-stream' --data-binary '@D:\audio\frame.pcm'
Invoke-RestMethod -Method Get -Uri 'http://127.0.0.1:8765/api/status' -Headers @{'X-Session-Id'=$sessionId}
```

Один запрос аудио содержит 2–20480 байт (до 0,64 с). Для эталона требуется достаточно чистой речи, поэтому одного кадра из примера обычно недостаточно для `/api/enrollment/finish`. Передавайте последовательные кадры до завершения записи. Операции с вопросом, TTS и отметкой показа доступны только в `integrated`.
