# Скачивает и распаковывает vosk-model-ru-0.42 в .\models (Windows PowerShell)
$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot
New-Item -ItemType Directory -Force -Path models | Out-Null
if (Test-Path "models\vosk-model-ru-0.42\am") { Write-Host "Модель уже установлена."; exit 0 }
$zip = "models\vosk-model-ru-0.42.zip"
Invoke-WebRequest -Uri "https://alphacephei.com/vosk/models/vosk-model-ru-0.42.zip" -OutFile $zip
Expand-Archive -Path $zip -DestinationPath models -Force
Remove-Item $zip
Write-Host "Готово: models\vosk-model-ru-0.42"
