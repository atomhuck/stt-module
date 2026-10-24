# Vosk и аналитика интервью

Этот Maven-модуль запускает Spring Boot с потоковым Vosk, WebSocket-интервью и Excel-отчётами. Необязательный Python-сервис Whisper уточняет завершённые ответы. Nemotron/CAM++ и общий браузерный режим находятся в соседнем модуле `server-prototype/java`.

- [Запуск, настройки моделей и банк вопросов](../docs/vosk-interview.md)
- [Правила анализа и состав отчёта](../docs/analytics.md)
- [WebSocket-протокол](../docs/api/websocket.md)
- [HTTP-контракты отчётов](../docs/api/interview-openapi.yaml) и [Whisper](../docs/api/whisper-openapi.yaml)
- [Общий запуск двух Java-модулей](../server-prototype/java/INTEGRATED_DEMO.md)

Из этого каталога: `mvn test`, `mvn package`, `mvn javadoc:javadoc`, `node --test src/test/js/interview-client-test.js`. Тест с реальной моделью Vosk пропускается, когда она не установлена.
