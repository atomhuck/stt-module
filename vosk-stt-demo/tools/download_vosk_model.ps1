param(
    [ValidateSet('vosk-model-ru-0.42', 'vosk-model-small-ru-0.22')]
    [string]$Model = 'vosk-model-ru-0.42'
)

$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$destination = Join-Path $root "models/$Model"
$base = "stt/ru/$Model"
$url = "https://huggingface.co/api/models/Derur/vosk-models/tree/main/$base`?recursive=true"
$files = @((Invoke-RestMethod -Uri $url) | Where-Object { $_.type -eq 'file' })
if ($files.Count -eq 0) { throw "Model file list is empty: $url" }
New-Item -ItemType Directory -Force -Path $destination | Out-Null

foreach ($entry in $files) {
    $relative = $entry.path.Substring($base.Length + 1)
    $target = Join-Path $destination $relative
    New-Item -ItemType Directory -Force -Path (Split-Path $target) | Out-Null
    $expectedHash = if ($entry.lfs) { $entry.lfs.oid } else { $null }
    if ((Test-Path $target) -and (Get-Item $target).Length -eq $entry.size) {
        if (!$expectedHash -or (Get-FileHash -Algorithm SHA256 $target).Hash -eq $expectedHash) {
            Write-Host "Already verified: $relative"
            continue
        }
    }
    $download = "$target.part"
    $fileUrl = "https://huggingface.co/Derur/vosk-models/resolve/main/$($entry.path)?download=true"
    $verified = $false
    for ($attempt = 1; $attempt -le 3 -and !$verified; $attempt++) {
        Write-Host "Downloading $relative ($($entry.size) bytes), attempt $attempt"
        & curl.exe -L --fail --silent --show-error --max-time 600 --output $download $fileUrl
        if ($LASTEXITCODE -ne 0) { continue }
        if ((Get-Item $download).Length -ne $entry.size) { continue }
        if ($expectedHash -and (Get-FileHash -Algorithm SHA256 $download).Hash -ne $expectedHash) { continue }
        Move-Item -LiteralPath $download -Destination $target -Force
        $verified = $true
    }
    if (!$verified) { throw "Could not download and verify $relative" }
}
if ($Model -eq 'vosk-model-ru-0.42' -and !(Test-Path (Join-Path $destination 'graph/words.txt'))) {
    throw 'The downloaded model has no graph/words.txt and cannot be loaded by Vosk.'
}
if ($Model -eq 'vosk-model-small-ru-0.22' -and
    (!(Test-Path (Join-Path $destination 'graph/Gr.fst')) -or
     !(Test-Path (Join-Path $destination 'graph/HCLr.fst')))) {
    throw 'The downloaded small model is missing its recognition graph.'
}
Write-Host "Model ready: $destination"
