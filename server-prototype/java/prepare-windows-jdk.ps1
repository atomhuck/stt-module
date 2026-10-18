param(
    [Parameter(Mandatory = $true)][string]$JdkHome,
    [Parameter(Mandatory = $true)][string]$NemoBin,
    [Parameter(Mandatory = $true)][string]$Target
)

$ErrorActionPreference = 'Stop'
$jdk = (Resolve-Path -LiteralPath $JdkHome).Path.TrimEnd('\')
$nemo = (Resolve-Path -LiteralPath $NemoBin).Path.TrimEnd('\')
$targetPath = [IO.Path]::GetFullPath($Target).TrimEnd('\')

if (-not (Test-Path -LiteralPath (Join-Path $jdk 'bin/java.exe') -PathType Leaf)) {
    throw "Не найден java.exe в $jdk"
}
if (-not (Test-Path -LiteralPath (Join-Path $nemo 'nemo_speech_asr_c.dll') -PathType Leaf)) {
    throw "Не найдена библиотека Nemotron в $nemo"
}
foreach ($source in @($jdk, $nemo)) {
    if ($targetPath.Equals($source, [StringComparison]::OrdinalIgnoreCase) -or
        $targetPath.StartsWith($source + '\', [StringComparison]::OrdinalIgnoreCase)) {
        throw 'Target должен быть отдельным каталогом вне JDK и Nemotron.'
    }
}
if (Test-Path -LiteralPath $targetPath) {
    if ((Test-Path -LiteralPath (Join-Path $targetPath 'bin/java.exe') -PathType Leaf) -and
        (Test-Path -LiteralPath (Join-Path $targetPath 'lib/modules') -PathType Leaf)) {
        Write-Host "Готовая копия Java runtime: $targetPath"
        exit 0
    }
    throw "Каталог $targetPath уже существует, но не является готовой копией Java runtime. Укажите новый Target."
}

New-Item -ItemType Directory -Path $targetPath | Out-Null
Copy-Item -LiteralPath (Join-Path $jdk 'bin') -Destination (Join-Path $targetPath 'bin') -Recurse
foreach ($directory in Get-ChildItem -LiteralPath $jdk -Directory) {
    if ($directory.Name -eq 'bin') { continue }
    New-Item -ItemType Junction -Path (Join-Path $targetPath $directory.Name) -Target $directory.FullName | Out-Null
}
Copy-Item -LiteralPath (Join-Path $jdk 'release') -Destination (Join-Path $targetPath 'release')
$runtimeDlls = Get-ChildItem -LiteralPath $nemo -File | Where-Object {
    $_.Name -match '^(msvcp140|vcruntime140|concrt140).*\.dll$'
}
if (-not $runtimeDlls) { throw "В $nemo нет библиотек Microsoft C++ runtime." }
$runtimeDlls | Copy-Item -Destination (Join-Path $targetPath 'bin') -Force
& (Join-Path $targetPath 'bin/java.exe') -version
Write-Host "Для IntelliJ IDEA выберите SDK: $targetPath"
