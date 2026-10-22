# STT module — локальное интервью с распознаванием и разделением голосов

Репозиторий содержит два Java-модуля: `server-prototype/java` разделяет говорящих с помощью Nemotron 3 и CAM++, а `vosk-stt-demo` распознаёт речь через Vosk, ведёт интервью, анализирует ответы и формирует Excel-отчёт. Необязательный Python-сервис Whisper уточняет завершённые ответы. Общий режим `integrated` связывает их локально.

## Быстрый старт без моделей

Нужны JDK 17+, Maven 3 и Node.js 22+ для браузерных тестов. Из корня репозитория:

```powershell
mvn -f server-prototype/java/pom.xml test
mvn -f vosk-stt-demo/pom.xml test
mvn -f server-prototype/java/pom.xml package -DskipTests
mvn -f vosk-stt-demo/pom.xml package -DskipTests
java -jar server-prototype/java/target/diarization-worker-1.0.0.jar --help
```

Тест с настоящей моделью Vosk автоматически пропускается, если `VOSK_MODEL_PATH` не указывает на распакованную модель. Модели, DLL, аудиозаписи и результаты в Git не хранятся.

## Запуск с моделями

- **Диаризация WAV или микрофона:** [режимы CLI](docs/cli.md) и [подготовка Nemotron/CAM++](server-prototype/java/README.md).
- **Отдельный Vosk и интервью:** [запуск сервиса](docs/vosk-interview.md), затем `http://127.0.0.1:8080/` или `/interview.html`.
- **Общее демо:** [пошаговая инструкция](server-prototype/java/INTEGRATED_DEMO.md). `DemoLauncher` запускает оба Java-компонента и Whisper из этого репозитория.

## Документация

Начните с [карты документации](docs/README.md). Там находятся [архитектура](docs/architecture/README.md), [протоколы](docs/api/README.md), [форматы результатов](docs/output-formats.md), [настройка и проверки](docs/development.md) и [ADR](docs/adr/README.md). Место для схемы архитектуры оставлено сокоманднику.

HTTP-сервер диаризации слушает только `127.0.0.1:8765`. Сервис Vosk/интервью по умолчанию слушает порт `8080`, а Whisper — `127.0.0.1:9000`; перед сетевым использованием настройте границы доступа отдельно. Оценки и задержки в документации являются описанием алгоритма или целями, пока не проверены живым прогоном.
