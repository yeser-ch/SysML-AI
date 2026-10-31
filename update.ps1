<#
  SysML AI Q&A – neuesten Stand von GitHub holen, bauen und in CATIA installieren.
  Einfach update.bat doppelklicken (fragt selbst nach Administratorrechten).
  Optionen werden an build.ps1 weitergegeben, z. B.: update.bat -CatiaDir "C:\Program Files\..."
#>
$ErrorActionPreference = 'Stop'
$Repo   = 'yeser-ch/SchroedingersBlock'
$Branch = 'feature/free-prompt-full-model'

Write-Host ''
Write-Host "== Neuesten Stand von GitHub laden ($Repo, Branch $Branch)" -ForegroundColor Cyan
$tmpRoot = [System.IO.Path]::GetTempPath()
$tmp = Join-Path $tmpRoot ('sysml-ai-qa-update-' + [guid]::NewGuid().ToString('N').Substring(0, 8))
New-Item -ItemType Directory -Force -Path $tmp | Out-Null
$zip = Join-Path $tmp 'source.zip'
try {
    [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
} catch { }
$url = "https://github.com/$Repo/archive/refs/heads/$Branch.zip?nocache=" + [DateTime]::UtcNow.Ticks
try {
    Invoke-WebRequest -Uri $url -OutFile $zip -UseBasicParsing
} catch {
    Write-Host "FEHLER: Download fehlgeschlagen: $($_.Exception.Message)" -ForegroundColor Red
    Write-Host '   Internetverbindung prüfen und erneut versuchen.'
    exit 1
}
Expand-Archive -Path $zip -DestinationPath $tmp -Force
$src = Get-ChildItem -LiteralPath $tmp -Directory | Select-Object -First 1
if (-not $src -or -not (Test-Path (Join-Path $src.FullName 'build.ps1'))) {
    Write-Host 'FEHLER: Im Download wurde build.ps1 nicht gefunden.' -ForegroundColor Red
    exit 1
}
Write-Host ("   Geladen: {0:dd.MM.yyyy HH:mm}" -f (Get-Date)) -ForegroundColor Green

& (Join-Path $src.FullName 'build.ps1') @args
$code = $LASTEXITCODE

# Installationspaket (ZIP für neue Nutzer) aus dem temporären Ordner in den eigenen Ordner dist\ übernehmen
$zips = @(Get-ChildItem -LiteralPath (Join-Path $src.FullName 'dist') -Filter '*.zip' -File -ErrorAction SilentlyContinue)
if ($zips.Count -gt 0) {
    $dist = Join-Path $PSScriptRoot 'dist'
    try {
        New-Item -ItemType Directory -Force -Path $dist | Out-Null
        foreach ($z in $zips) { Copy-Item $z.FullName (Join-Path $dist $z.Name) -Force }
        Write-Host "   Installationspaket für neue Nutzer: $(Join-Path $dist $zips[0].Name)" -ForegroundColor Green
    } catch { }
}

# Neueste Version der Skripte neben die eigene Datei legen (falls sie sich geändert hat)
foreach ($f in @('update.bat', 'update.ps1', 'build.bat', 'build.ps1', 'install.bat', 'install.ps1', 'INSTALL.md')) {
    try { Copy-Item (Join-Path $src.FullName $f) (Join-Path $PSScriptRoot $f) -Force -ErrorAction Stop } catch { }
}
Remove-Item -LiteralPath $tmp -Recurse -Force -ErrorAction SilentlyContinue
exit $code
