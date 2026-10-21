param(
    [string]$Source = '',
    [string]$Destination = 'C:\Users\Public\NemotronTest\vosk-model-ru-0.42-native'
)

$ErrorActionPreference = 'Stop'
if (-not $Source) {
    $workspace = (Resolve-Path (Join-Path $PSScriptRoot '..\..\..\..')).Path
    $Source = Join-Path $workspace 'vosk-stt-demo\models\vosk-model-ru-0.42'
}
$sourceRoot = (Resolve-Path -LiteralPath $Source).Path.TrimEnd('\')
if (-not (Test-Path -LiteralPath (Join-Path $sourceRoot 'graph\words.txt'))) {
    throw "Vosk 0.42 is not unpacked at $sourceRoot"
}
if (-not (Test-Path -LiteralPath $Destination)) {
    New-Item -ItemType Directory -Path $Destination | Out-Null
}
$destinationRoot = (Resolve-Path -LiteralPath $Destination).Path.TrimEnd('\')
if ($sourceRoot.Equals($destinationRoot, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'Source and destination must differ.'
}
$created = 0
foreach ($file in Get-ChildItem -LiteralPath $sourceRoot -Recurse -File) {
    $relative = $file.FullName.Substring($sourceRoot.Length).TrimStart('\')
    $target = Join-Path $destinationRoot $relative
    if (Test-Path -LiteralPath $target) {
        if ((Get-Item -LiteralPath $target).Length -ne $file.Length) {
            throw "Existing file has a different size: $target"
        }
        continue
    }
    New-Item -ItemType Directory -Force -Path (Split-Path $target) | Out-Null
    New-Item -ItemType HardLink -Path $target -Target $file.FullName | Out-Null
    $created++
}
$count = (Get-ChildItem -LiteralPath $destinationRoot -Recurse -File | Measure-Object).Count
Write-Host "Vosk 0.42: $count files at $destinationRoot ($created new hard links)."
Write-Host "Use VOSK_MODEL_PATH=$destinationRoot"
