<#
  SysML AI Q&A – neuesten Stand von GitHub holen, bauen und in CATIA installieren.
  Einfach update.bat doppelklicken (fragt selbst nach Administratorrechten).
  Optionen werden an build.ps1 weitergegeben, z. B.: update.bat -CatiaDir "C:\Program Files\..."

  GitHub-Release: Ist die GitHub CLI (gh) installiert und angemeldet und hat das Konto Schreibrechte,
  wird das fertige Paket danach automatisch als neuestes Release veröffentlicht
  (neue Version: neues Release; gleiche Version: ZIP im vorhandenen Release ersetzt).
  Ohne gh oder ohne Schreibrechte wird dieser Schritt übersprungen. Abschalten: update.bat -NoPublish
#>
$ErrorActionPreference = 'Stop'
$Repo   = 'yeser-ch/SysML-AI'
$Branch = 'feature/free-prompt-full-model'

$NoPublish = [bool]($args | Where-Object { "$_" -eq '-NoPublish' })

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

# Paket als neuestes GitHub-Release veröffentlichen (nur mit GitHub CLI und Schreibrechten)
function Publish-Release($zipPath) {
    $ErrorActionPreference = 'Continue'
    Write-Host ''
    Write-Host '== GitHub-Release aktualisieren' -ForegroundColor Cyan
    if ($NoPublish) { Write-Host '   Übersprungen (-NoPublish).'; return }
    if (-not (Get-Command gh -ErrorAction SilentlyContinue)) {
        Write-Host '   Übersprungen: GitHub CLI (gh) ist nicht installiert. Einrichtung: INSTALL.md, Abschnitt "Neue Version an andere verteilen".' -ForegroundColor Yellow
        return
    }
    $null = & gh auth status 2>&1
    if ($LASTEXITCODE -ne 0) {
        Write-Host '   Übersprungen: gh ist nicht angemeldet (einmalig "gh auth login" ausführen).' -ForegroundColor Yellow
        return
    }
    $canPush = (& gh api "repos/$Repo" --jq '.permissions.push' 2>$null | Out-String).Trim()
    if ($canPush -ne 'true') {
        Write-Host "   Übersprungen: Das angemeldete GitHub-Konto hat keine Schreibrechte für $Repo." -ForegroundColor Yellow
        return
    }
    $name = [System.IO.Path]::GetFileNameWithoutExtension($zipPath)
    if ($name -notmatch '^SysML-AI-QA_v(.+)$' -or $Matches[1] -eq 'dev') {
        Write-Host "   Übersprungen: Version aus '$name' nicht erkennbar." -ForegroundColor Yellow
        return
    }
    $version = $Matches[1]
    $tag = "v$version"

    # Release-Text: Abschnitt dieser Version aus AENDERUNGEN.md
    $notes = ''
    $changes = Join-Path $src.FullName 'AENDERUNGEN.md'
    $short = ($version -split '-')[0]
    if (Test-Path $changes) {
        $take = $false; $lines = @()
        foreach ($l in (Get-Content $changes -Encoding UTF8)) {
            if ($l -match '^## ') {
                if ($take) { break }
                if ($l -match ('^## v' + [regex]::Escape($short) + '(\s|$)')) { $take = $true; continue }
            }
            if ($take) { $lines += $l }
        }
        $notes = ($lines -join "`n").Trim()
    }
    $head = "## Installation`nUnten bei **Assets** die Datei ``$name.zip`` laden, entpacken und ``install.bat`` starten. Anleitung: [INSTALL.md](https://github.com/$Repo/blob/$Branch/INSTALL.md). Eine vorhandene Version wird überschrieben; API-Key und Ergebnisse bleiben erhalten.`n`n"
    $tail = "`n`nAlle Änderungen: [AENDERUNGEN.md](https://github.com/$Repo/blob/$Branch/AENDERUNGEN.md)"
    if ($notes) { $notes = $head + "## Neu in $version`n" + $notes + $tail } else { $notes = $head.Trim() + $tail }
    $notesFile = Join-Path $tmp 'release-notes.md'
    [System.IO.File]::WriteAllText($notesFile, $notes, (New-Object System.Text.UTF8Encoding($false)))

    $null = & gh release view $tag -R $Repo 2>&1
    if ($LASTEXITCODE -eq 0) {
        & gh release upload $tag $zipPath -R $Repo --clobber
        if ($LASTEXITCODE -eq 0) { & gh release edit $tag -R $Repo --latest --notes-file $notesFile }
        $what = 'aktualisiert'
    } else {
        & gh release create $tag $zipPath -R $Repo --target $Branch --title "SysML AI Q&A $version" --notes-file $notesFile --latest
        $what = 'veröffentlicht'
    }
    if ($LASTEXITCODE -eq 0) {
        Write-Host "   Release $tag $what (Latest): https://github.com/$Repo/releases/latest" -ForegroundColor Green
    } else {
        Write-Host "   Release $tag konnte nicht hochgeladen werden (Meldung siehe oben). Das Plugin ist trotzdem installiert." -ForegroundColor Yellow
    }
}
if ($code -eq 0 -and $zips.Count -gt 0) { Publish-Release $zips[0].FullName }

# Neueste Version der Skripte neben die eigene Datei legen (falls sie sich geändert hat)
foreach ($f in @('update.bat', 'update.ps1', 'build.bat', 'build.ps1', 'install.bat', 'install.ps1', 'INSTALL.md')) {
    try { Copy-Item (Join-Path $src.FullName $f) (Join-Path $PSScriptRoot $f) -Force -ErrorAction Stop } catch { }
}
Remove-Item -LiteralPath $tmp -Recurse -Force -ErrorAction SilentlyContinue
exit $code
