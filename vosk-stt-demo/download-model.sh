#!/usr/bin/env bash
# Скачивает и распаковывает vosk-model-ru-0.42 (~1.8 ГБ в архиве) в ./models
set -euo pipefail
cd "$(dirname "$0")"
mkdir -p models
if [ -d models/vosk-model-ru-0.42/am ]; then
  echo "Модель уже установлена."; exit 0
fi
curl -L -C - -o models/vosk-model-ru-0.42.zip https://alphacephei.com/vosk/models/vosk-model-ru-0.42.zip
unzip -q models/vosk-model-ru-0.42.zip -d models
rm models/vosk-model-ru-0.42.zip
echo "Готово: models/vosk-model-ru-0.42"
