<#
  SysML AI Q&A – fertiges Plugin in CATIA Magic installieren (ohne Java, ohne Git, ohne Kompilieren).
  ------------------------------------------------------------------
  Einfach install.bat doppelklicken. Das Skript
    1. findet die CATIA-Installation,
    2. findet den Plugin-Ordner (vorhandene Installation oder <CATIA>\plugins\de.hm.sysmlaiqa.plugin),
    3. verschiebt eine Installation der Ausgangsversion (andere Plugin-ID) nach Dokumente\SysML-AI-QA\backup,
    4. kopiert JAR, plugin.xml, Export-Templates und System-Prompt dorthin
       (eine vorhandene JAR wird als .bak gesichert, ein von euch geänderter System-Prompt bleibt erhalten).

  Wird auch von build.ps1 aufgerufen (dann mit -CatiaDir und -SourceDir).

  Optionen (nur falls die automatische Suche nicht klappt):
    install.bat -CatiaDir "C:\Program Files\Magic Systems of Systems Architect"
    install.bat -PluginDir "C:\Program Files\Magic Systems of Systems Architect\plugins\de.hm.sysmlaiqa.plugin"
#>
param(
    [string]$CatiaDir = "",
    [string]$PluginDir = "",
    [string]$SourceDir = "",
    [switch]$NonInteractive
)

$ErrorActionPreference = 'Stop'
$PluginId = 'de.hm.sysmlaiqa.plugin'
$JarName = 'sysml-ai-qa.jar'
# Ausgangsversion mit anderer Plugin-ID: wird beim Installieren aus dem plugins-Ordner entfernt (sonst lädt CATIA beide)
$LegacyPluginId = 'com.schroedingersblock.plugin'
if (-not $SourceDir) { $SourceDir = Join-Path $PSScriptRoot 'plugin' }

function Step($m) { Write-Host ''; Write-Host "== $m" -ForegroundColor Cyan }
function Info($m) { Write-Host "   $m" }
function Ok($m)   { Write-Host "   $m" -ForegroundColor Green }
function Warn($m) { Write-Host "   $m" -ForegroundColor Yellow }
function Fail($m) {
    Write-Host ''
    Write-Host "FEHLER: $m" -ForegroundColor Red
    exit 1
}
function Ask($question) {
    if ($NonInteractive) { return '' }
    return (Read-Host "   $question").Trim().Trim('"')
}
function Fwd($p) { return ($p -replace '\\', '/') }

# ─────────────────────────────────────────────────────────────────────────────
# Paket prüfen
# ─────────────────────────────────────────────────────────────────────────────
$need = @($JarName, 'plugin.xml', 'data_extraction/sysml_flat_export.vm',
          'data_extraction/sysml_partial_export.vm', 'prompts/System_Prompt.md')
$missing = @($need | Where-Object { -not (Test-Path (Join-Path $SourceDir $_)) })
if ($missing.Count -gt 0) {
    Fail ("Im Ordner '$SourceDir' fehlen Dateien: $($missing -join ', ').`n" +
          "   Wurde die ZIP-Datei vollständig entpackt? (Rechtsklick auf die ZIP -> 'Alle extrahieren', danach install.bat im entpackten Ordner starten)")
}
$version = ''
$verFile = Join-Path $SourceDir 'VERSION.txt'
if (Test-Path $verFile) { $version = (Get-Content $verFile -Raw).Trim() }

# ─────────────────────────────────────────────────────────────────────────────
Step '1/4  CATIA-Installation suchen'
# ─────────────────────────────────────────────────────────────────────────────
# CATIA Magic / Magic Systems of Systems Architect / Cameo: Hauptordner enthält 'bin', 'lib' (mit .jar-Dateien) und 'plugins'
function Test-CatiaDir($d) {
    if (-not $d -or -not (Test-Path $d)) { return $false }
    if ((Test-Path (Join-Path $d 'lib/md.jar')) -or (Test-Path (Join-Path $d 'lib/md_api.jar'))) { return $true }
    $lib = Join-Path $d 'lib'
    if (-not (Test-Path (Join-Path $d 'bin')) -or -not (Test-Path $lib) -or -not (Test-Path (Join-Path $d 'plugins'))) { return $false }
    return [bool](Get-ChildItem -LiteralPath $lib -Recurse -Filter '*.jar' -File -ErrorAction SilentlyContinue | Select-Object -First 1)
}
function Normalize-CatiaDir($d) {
    if (-not $d) { return '' }
    # Falls jemand den Pfad zur .exe oder zum bin-Ordner einfügt: auf den Hauptordner zurückgehen
    if ($d -match '\.exe$') { $d = Split-Path $d -Parent }
    if ((Split-Path $d -Leaf) -eq 'bin') { $d = Split-Path $d -Parent }
    return $d
}

if ($CatiaDir) {
    $CatiaDir = Normalize-CatiaDir $CatiaDir
    if (-not (Test-CatiaDir $CatiaDir)) {
        Fail "Im Ordner '$CatiaDir' gibt es keine CATIA-Installation. Bitte den Hauptordner angeben (der mit 'bin', 'lib' und 'plugins')."
    }
}
if (-not $CatiaDir) {
    $roots = @($env:ProgramFiles, ${env:ProgramFiles(x86)}, 'C:\Program Files', 'D:\Program Files', 'C:\', 'D:\') |
             Where-Object { $_ -and (Test-Path $_) } | Select-Object -Unique
    $candidates = New-Object System.Collections.Generic.List[string]
    foreach ($r in $roots) {
        foreach ($d1 in Get-ChildItem -LiteralPath $r -Directory -ErrorAction SilentlyContinue) {
            if ($d1.Name -in @('Windows', '$Recycle.Bin', 'ProgramData', 'Users')) { continue }
            if (Test-CatiaDir $d1.FullName) { $candidates.Add($d1.FullName) }
            foreach ($d2 in Get-ChildItem -LiteralPath $d1.FullName -Directory -ErrorAction SilentlyContinue) {
                if (Test-CatiaDir $d2.FullName) { $candidates.Add($d2.FullName) }
            }
        }
    }
    $candidates = @($candidates | Select-Object -Unique)
    if ($candidates.Count -gt 0) {
        $CatiaDir = $candidates | Sort-Object @{ Expression = { ($_ -match '2026') -as [int] }; Descending = $true },
                                              @{ Expression = { ($_ -match 'Magic|CATIA|Cameo|Architect') -as [int] }; Descending = $true } |
                    Select-Object -First 1
        if ($candidates.Count -gt 1) {
            Info 'Mehrere Installationen gefunden (genommen wird die erste passende):'
            $candidates | ForEach-Object { Info "  - $_" }
        }
    }
}
if (-not $CatiaDir) {
    Warn 'CATIA wurde nicht automatisch gefunden.'
    Info "So findest du den Ordner: Startmenü -> Rechtsklick auf CATIA Magic -> 'Dateispeicherort öffnen'"
    Info "-> nochmal Rechtsklick auf die Verknüpfung -> 'Dateispeicherort öffnen' -> eine Ebene hoch (Ordner mit 'bin' und 'lib')."
    $CatiaDir = Normalize-CatiaDir (Ask 'Pfad zum CATIA-Ordner einfügen und Enter drücken')
    if (-not $CatiaDir) { Fail "Kein CATIA-Ordner angegeben. install.bat erneut starten und den Pfad einfügen (oder: install.bat -CatiaDir ""<Pfad>"")." }
    if (-not (Test-CatiaDir $CatiaDir)) { Fail "Im Ordner '$CatiaDir' gibt es keine CATIA-Installation (Ordner 'bin', 'lib' und 'plugins')." }
}
Ok "CATIA: $CatiaDir"

# ─────────────────────────────────────────────────────────────────────────────
Step '2/4  Plugin-Ordner bestimmen'
# ─────────────────────────────────────────────────────────────────────────────
if (-not $PluginDir) {
    # Vorhandene Installation bevorzugen
    $pluginCandidates = New-Object System.Collections.Generic.List[string]
    $pluginCandidates.Add((Join-Path $CatiaDir "plugins/$PluginId"))
    foreach ($base in @($env:LOCALAPPDATA, $env:APPDATA, $HOME) | Where-Object { $_ -and (Test-Path $_) } | Select-Object -Unique) {
        foreach ($magic in Get-ChildItem -LiteralPath $base -Directory -Force -Filter '.magic*' -ErrorAction SilentlyContinue) {
            foreach ($ver in Get-ChildItem -LiteralPath $magic.FullName -Directory -Force -ErrorAction SilentlyContinue) {
                $pluginCandidates.Add((Join-Path $ver.FullName "plugins/$PluginId"))
            }
        }
    }
    $existing = @($pluginCandidates | Select-Object -Unique | Where-Object { Test-Path (Join-Path $_ $JarName) })
    if ($existing.Count -gt 0) {
        $PluginDir = $existing | Sort-Object { (Get-Item (Join-Path $_ $JarName)).LastWriteTime } -Descending | Select-Object -First 1
        Info 'Vorhandene Installation gefunden – sie wird aktualisiert.'
        if ($existing.Count -gt 1) {
            Info 'Mehrere Plugin-Ordner gefunden (genommen wird der mit der neuesten JAR):'
            $existing | ForEach-Object { Info "  - $_" }
        }
    } else {
        $PluginDir = Join-Path $CatiaDir "plugins/$PluginId"
        Info 'Neuinstallation.'
    }
} elseif ((Split-Path $PluginDir -Leaf) -ne $PluginId) {
    $PluginDir = Join-Path $PluginDir $PluginId
}
try {
    New-Item -ItemType Directory -Force -Path $PluginDir | Out-Null
    $probe = Join-Path $PluginDir '.write-test'
    [System.IO.File]::WriteAllText($probe, 'x')
    Remove-Item $probe -Force
} catch {
    Fail ("Keine Schreibrechte im Plugin-Ordner '$PluginDir'.`n" +
          "   Das Skript braucht Administratorrechte: install.bat (bzw. build.bat / update.bat) mit Rechtsklick -> 'Als Administrator ausführen' starten.")
}
Ok "Plugin-Ordner: $PluginDir"

# Läuft CATIA noch? (Die JAR ist dann gesperrt, und CATIA lädt Plugins nur beim Start)
function Get-CatiaProcess {
    Get-Process -ErrorAction SilentlyContinue | Where-Object {
        try { $_.Path -and (Fwd $_.Path).StartsWith((Fwd $CatiaDir), [System.StringComparison]::OrdinalIgnoreCase) } catch { $false }
    }
}
while (Get-CatiaProcess) {
    Warn 'CATIA läuft noch. Bitte CATIA komplett schließen.'
    if ($NonInteractive) { Fail 'CATIA läuft noch.' }
    [void](Ask 'Danach Enter drücken')
}

# ─────────────────────────────────────────────────────────────────────────────
Step '3/4  Ältere Version mit anderer Plugin-ID entfernen'
# ─────────────────────────────────────────────────────────────────────────────
# CATIA lädt jeden Unterordner von 'plugins' mit plugin.xml. Bliebe die Ausgangsversion liegen, wären beide aktiv.
# Sie wird deshalb nicht gelöscht, sondern nach Dokumente\SysML-AI-QA\backup verschoben.
$legacyCandidates = New-Object System.Collections.Generic.List[string]
$legacyCandidates.Add((Join-Path $CatiaDir "plugins/$LegacyPluginId"))
foreach ($base in @($env:LOCALAPPDATA, $env:APPDATA, $HOME) | Where-Object { $_ -and (Test-Path $_) } | Select-Object -Unique) {
    foreach ($magic in Get-ChildItem -LiteralPath $base -Directory -Force -Filter '.magic*' -ErrorAction SilentlyContinue) {
        foreach ($ver in Get-ChildItem -LiteralPath $magic.FullName -Directory -Force -ErrorAction SilentlyContinue) {
            $legacyCandidates.Add((Join-Path $ver.FullName "plugins/$LegacyPluginId"))
        }
    }
}
$legacyDirs = @($legacyCandidates | Select-Object -Unique | Where-Object { Test-Path -LiteralPath $_ })
$legacyPrompt = $null
$legacyBackup = $null
if ($legacyDirs.Count -eq 0) {
    Info 'Keine gefunden.'
}
$i = 0
foreach ($d in $legacyDirs) {
    $i++
    $stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
    $dest = Join-Path $HOME ("Documents/SysML-AI-QA/backup/old-plugin_$stamp" + $(if ($legacyDirs.Count -gt 1) { "_$i" } else { '' }))
    try {
        New-Item -ItemType Directory -Force -Path (Split-Path $dest -Parent) | Out-Null
        Copy-Item -LiteralPath $d -Destination $dest -Recurse -Force
        Remove-Item -LiteralPath $d -Recurse -Force
    } catch {
        Fail ("Die ältere Version in '$d' konnte nicht entfernt werden: $($_.Exception.Message)`n" +
              "   Ist CATIA geschlossen und läuft das Skript als Administrator?")
    }
    Ok "Ältere Version verschoben nach: $dest"
    $legacyBackup = $dest
    $p = Join-Path $dest 'prompts/System_Prompt.md'
    if (Test-Path $p) { $legacyPrompt = $p }
}

# ─────────────────────────────────────────────────────────────────────────────
Step '4/4  Dateien kopieren'
# ─────────────────────────────────────────────────────────────────────────────
$targetJar = Join-Path $PluginDir $JarName
try {
    if (Test-Path $targetJar) {
        Copy-Item $targetJar "$targetJar.bak" -Force
        Info "Alte JAR gesichert: $JarName.bak"
    }
    Copy-Item (Join-Path $SourceDir $JarName) $targetJar -Force
    Copy-Item (Join-Path $SourceDir 'plugin.xml') (Join-Path $PluginDir 'plugin.xml') -Force

    $dx = Join-Path $PluginDir 'data_extraction'
    New-Item -ItemType Directory -Force -Path $dx | Out-Null
    foreach ($vm in @('sysml_flat_export.vm', 'sysml_partial_export.vm')) {
        Copy-Item (Join-Path $SourceDir "data_extraction/$vm") (Join-Path $dx $vm) -Force
    }

    $promptsDir = Join-Path $PluginDir 'prompts'
    New-Item -ItemType Directory -Force -Path $promptsDir | Out-Null
    $newPrompt = Join-Path $SourceDir 'prompts/System_Prompt.md'
    $installedPrompt = Join-Path $promptsDir 'System_Prompt.md'
    if (-not (Test-Path $installedPrompt) -and $legacyPrompt -and
        ((Get-FileHash $legacyPrompt).Hash -ne (Get-FileHash $newPrompt).Hash)) {
        # Selbst geänderten System-Prompt der älteren Version übernehmen
        Copy-Item $legacyPrompt $installedPrompt -Force
        Copy-Item $newPrompt "$installedPrompt.new" -Force
        Warn 'Der System-Prompt der älteren Version wurde übernommen. Neue Version liegt daneben als System_Prompt.md.new.'
    } elseif ((Test-Path $installedPrompt) -and ((Get-FileHash $installedPrompt).Hash -ne (Get-FileHash $newPrompt).Hash)) {
        Copy-Item $newPrompt "$installedPrompt.new" -Force
        Warn 'System_Prompt.md wurde von euch geändert und bleibt erhalten. Neue Version liegt daneben als System_Prompt.md.new.'
    } else {
        Copy-Item $newPrompt $installedPrompt -Force
    }
} catch {
    $msg = $_.Exception.Message
    if ($msg -match 'verweigert|denied|UnauthorizedAccess') {
        Fail "Kopieren fehlgeschlagen: $msg`n   Bitte mit Rechtsklick -> 'Als Administrator ausführen' starten (und CATIA geschlossen lassen)."
    }
    Fail "Kopieren fehlgeschlagen: $msg`n   Ist CATIA wirklich geschlossen?"
}

Write-Host ''
if ($version) { Ok "Fertig! SysML AI Q&A $version ist installiert." } else { Ok 'Fertig! SysML AI Q&A ist installiert.' }
Info 'Jetzt CATIA starten. Kontrolle: Window -> Notification Window zeigt "Plugin loaded (version ...)".'
Info 'Aufruf: Rechtsklick auf ein Element -> "Ask AI (SysML AI Q&A)" oder oben in der Menüleiste "Ask AI".'
if (Test-Path "$targetJar.bak") { Info "Zurück zur vorherigen Version: $JarName.bak im Plugin-Ordner wieder in $JarName umbenennen." }
if ($legacyBackup) {
    Info 'API-Key und Einstellungen der älteren Version übernimmt das Plugin beim ersten Start automatisch.'
    Info "Zurück zur älteren Version: den Ordner '$PluginId' im plugins-Ordner löschen und den Ordner aus"
    Info "   $legacyBackup  wieder als '$LegacyPluginId' in den plugins-Ordner kopieren."
}
exit 0
