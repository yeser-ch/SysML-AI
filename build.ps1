<#
  Schrödinger's Block – Plugin bauen und in CATIA Magic installieren
  ------------------------------------------------------------------
  Einfach build.bat doppelklicken. Das Skript
    1. findet die CATIA-Installation (für die Bibliotheken),
    2. findet ein Java JDK (javac),
    3. kompiliert den Code in src\ zu build\schroedingers-block.jar,
    4. kopiert JAR, plugin.xml, Export-Templates und System-Prompt in den Plugin-Ordner von CATIA
       (die alte JAR wird als .bak gesichert).

  Optionen (nur falls die automatische Suche nicht klappt):
    build.bat -CatiaDir "C:\Program Files\...\CATIA Magic ... 2026x"
    build.bat -PluginDir "C:\Users\<du>\AppData\Local\.magic.systems.of.systems.architect\2026x\plugins\com.schroedingersblock.plugin"
    build.bat -JdkDir "C:\Program Files\Eclipse Adoptium\jdk-21..."
    build.bat -NoInstall      (nur bauen, nichts kopieren)
#>
param(
    [string]$CatiaDir = "",
    [string]$PluginDir = "",
    [string]$JdkDir = "",
    [switch]$NoInstall,
    [switch]$NonInteractive
)

$ErrorActionPreference = 'Stop'
$Repo = $PSScriptRoot
$PluginId = 'com.schroedingersblock.plugin'
$JarName = 'schroedingers-block.jar'
$Release = 17          # wie die Vorgängerversion (lief in CATIA Magic 2026x)
$OnWindows = ($env:OS -eq 'Windows_NT')
$ExeSuffix = if ($OnWindows) { '.exe' } else { '' }
$PathSep = if ($OnWindows) { ';' } else { ':' }

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

# Führt ein Programm aus und liefert Ausgabe + Exitcode (ohne dass PowerShell bei stderr abbricht)
function Run-Native($exe, [string[]]$arguments) {
    $old = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $out = & $exe @arguments 2>&1 | ForEach-Object { "$_" }
        $code = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $old
    }
    return [pscustomobject]@{ Output = ($out -join "`n"); Code = $code }
}

if (-not (Test-Path (Join-Path $Repo 'src'))) {
    Fail "build.ps1 muss im Hauptordner des Repositorys liegen (dort, wo der Ordner 'src' ist)."
}

# ─────────────────────────────────────────────────────────────────────────────
Step '1/5  CATIA-Installation suchen'
# ─────────────────────────────────────────────────────────────────────────────
function Test-CatiaDir($d) {
    if (-not $d -or -not (Test-Path $d)) { return $false }
    return (Test-Path (Join-Path $d 'lib/md.jar')) -or (Test-Path (Join-Path $d 'lib/md_api.jar'))
}

if ($CatiaDir -and -not (Test-CatiaDir $CatiaDir)) {
    Fail "Im Ordner '$CatiaDir' gibt es keine CATIA-Bibliotheken (lib\md.jar). Bitte den Hauptordner der Installation angeben (der mit 'bin' und 'lib')."
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
        # Bevorzugt 2026x und Magic/CATIA im Namen
        $CatiaDir = $candidates | Sort-Object @{ Expression = { ($_ -match '2026') -as [int] }; Descending = $true },
                                              @{ Expression = { ($_ -match 'Magic|CATIA|Cameo') -as [int] }; Descending = $true } |
                    Select-Object -First 1
        if ($candidates.Count -gt 1) {
            Info 'Mehrere Installationen gefunden:'
            $candidates | ForEach-Object { Info "  - $_" }
        }
    }
}
if (-not $CatiaDir) {
    Warn 'CATIA wurde nicht automatisch gefunden.'
    Info "So findest du den Ordner: Startmenü -> Rechtsklick auf CATIA Magic -> 'Dateispeicherort öffnen'"
    Info "-> nochmal Rechtsklick auf die Verknüpfung -> 'Dateispeicherort öffnen' -> eine Ebene hoch (Ordner mit 'bin' und 'lib')."
    $CatiaDir = Ask 'Pfad zum CATIA-Ordner einfügen und Enter drücken'
    if (-not (Test-CatiaDir $CatiaDir)) { Fail "Im Ordner '$CatiaDir' gibt es keine CATIA-Bibliotheken (lib\md.jar)." }
}
Ok "CATIA: $CatiaDir"

# ─────────────────────────────────────────────────────────────────────────────
Step '2/5  Java JDK suchen (javac, mindestens Version 17)'
# ─────────────────────────────────────────────────────────────────────────────
function Get-JdkMajor($jdk) {
    $javac = Join-Path $jdk ("bin/javac" + $ExeSuffix)
    if (-not (Test-Path $javac)) { return 0 }
    $r = Run-Native $javac @('-version')
    if ($r.Output -match 'javac\s+(\d+)(\.(\d+))?') {
        $major = [int]$Matches[1]
        if ($major -eq 1 -and $Matches[3]) { $major = [int]$Matches[3] }   # alte Schreibweise 1.8
        return $major
    }
    return 0
}

$jdkCandidates = New-Object System.Collections.Generic.List[string]
if ($JdkDir) { $jdkCandidates.Add($JdkDir) }
if ($env:JAVA_HOME) { $jdkCandidates.Add($env:JAVA_HOME) }
$cmd = Get-Command ("javac" + $ExeSuffix) -ErrorAction SilentlyContinue
if ($cmd) { $jdkCandidates.Add((Split-Path (Split-Path $cmd.Source -Parent) -Parent)) }
$jdkCandidates.Add((Join-Path $CatiaDir 'jre'))
$jdkCandidates.Add((Join-Path $CatiaDir 'jdk'))
$jdkRoots = @('C:\Program Files\Eclipse Adoptium', 'C:\Program Files\Java', 'C:\Program Files\Microsoft',
              'C:\Program Files\Amazon Corretto', 'C:\Program Files\Zulu', 'C:\Program Files\BellSoft',
              (Join-Path $HOME '.jdks'))
foreach ($r in $jdkRoots) {
    if (Test-Path $r) { Get-ChildItem -LiteralPath $r -Directory -ErrorAction SilentlyContinue | ForEach-Object { $jdkCandidates.Add($_.FullName) } }
}

$Jdk = $null; $JdkMajor = 0
foreach ($c in ($jdkCandidates | Select-Object -Unique)) {
    $m = Get-JdkMajor $c
    if ($m -ge 17 -and $m -gt $JdkMajor) { $Jdk = $c; $JdkMajor = $m }
    if ($JdkDir -and $c -eq $JdkDir) { if ($m -ge 17) { break } }
}
if (-not $Jdk) {
    Fail ("Kein Java JDK (Version 17 oder neuer) gefunden.`n" +
          "   Installiere z. B. 'Eclipse Temurin JDK 21' von https://adoptium.net (Windows-MSI)`n" +
          "   und setze bei der Installation den Haken bei 'Set JAVA_HOME variable'. Danach build.bat erneut starten.")
}
$Javac = Join-Path $Jdk ("bin/javac" + $ExeSuffix)
$JarTool = Join-Path $Jdk ("bin/jar" + $ExeSuffix)
Ok "JDK: $Jdk (Java $JdkMajor)"

# ─────────────────────────────────────────────────────────────────────────────
Step '3/5  Code kompilieren'
# ─────────────────────────────────────────────────────────────────────────────
Push-Location $Repo
$previousCwd = [System.IO.Directory]::GetCurrentDirectory()
[System.IO.Directory]::SetCurrentDirectory($Repo)   # damit javac/jar die relativen Pfade finden
try {
    $buildDir = Join-Path $Repo 'build'
    $classesDir = Join-Path $buildDir 'classes'
    if (Test-Path $classesDir) { Remove-Item $classesDir -Recurse -Force }
    New-Item -ItemType Directory -Force -Path $classesDir | Out-Null

    # Alle Bibliotheken aus CATIA (lib + plugins, ohne unser eigenes Plugin)
    $jars = @()
    foreach ($sub in @('lib', 'plugins')) {
        $p = Join-Path $CatiaDir $sub
        if (Test-Path $p) {
            $jars += Get-ChildItem -LiteralPath $p -Recurse -Filter '*.jar' -File -ErrorAction SilentlyContinue |
                     Where-Object { $_.FullName -notmatch 'schroedingersblock' } |
                     ForEach-Object { Fwd $_.FullName }
        }
    }
    Info "$($jars.Count) CATIA-Bibliotheken gefunden."
    if ($jars.Count -eq 0) { Fail "Keine .jar-Dateien in '$CatiaDir\lib' gefunden." }

    # Quelldateien relativ zum Repo (vermeidet Probleme mit Umlauten im Benutzerpfad)
    $sources = Get-ChildItem -Path (Join-Path $Repo 'src') -Recurse -Filter '*.java' -File |
               ForEach-Object { Fwd ($_.FullName.Substring($Repo.Length).TrimStart('\', '/')) }
    Info "$($sources.Count) Quelldateien."

    function Invoke-Javac([bool]$withRelease) {
        $lines = New-Object System.Collections.Generic.List[string]
        $lines.Add('-encoding'); $lines.Add('UTF-8')
        $lines.Add('-nowarn')
        if ($withRelease) { $lines.Add('--release'); $lines.Add("$Release") }
        $lines.Add('-d'); $lines.Add('"build/classes"')
        $lines.Add('-cp'); $lines.Add('"' + ($jars -join $PathSep) + '"')
        foreach ($s in $sources) { $lines.Add('"' + $s + '"') }
        $argFile = Join-Path $buildDir 'javac-args.txt'
        [System.IO.File]::WriteAllLines($argFile, $lines, (New-Object System.Text.UTF8Encoding($false)))
        return Run-Native $Javac @('@build/javac-args.txt')
    }

    $r = Invoke-Javac $true
    if ($r.Code -ne 0 -and $r.Output -match 'wrong version|falsche Version|class file has wrong') {
        Warn "CATIA-Bibliotheken sind für eine neuere Java-Version gebaut – kompiliere ohne --release $Release."
        $r = Invoke-Javac $false
    }
    if ($r.Code -ne 0) {
        Write-Host $r.Output
        if ($r.Output -match 'package com\.nomagic') {
            Fail "Die CATIA-Bibliotheken wurden nicht gefunden – ist '$CatiaDir' der richtige Ordner?"
        }
        Fail 'Kompilieren fehlgeschlagen (Meldungen siehe oben). Bitte die Ausgabe an Claude schicken.'
    }
    Ok 'Kompiliert.'

    # ─────────────────────────────────────────────────────────────────────────
    Step '4/5  JAR erstellen'
    # ─────────────────────────────────────────────────────────────────────────
    $jarPath = Join-Path $buildDir $JarName
    if (Test-Path $jarPath) { Remove-Item $jarPath -Force }
    $r = Run-Native $JarTool @('cf', "build/$JarName", '-C', 'build/classes', '.')
    if ($r.Code -ne 0 -or -not (Test-Path $jarPath)) { Write-Host $r.Output; Fail 'JAR konnte nicht erstellt werden.' }
    Ok ("JAR: $jarPath  ({0:N0} KB)" -f ((Get-Item $jarPath).Length / 1KB))
} finally {
    [System.IO.Directory]::SetCurrentDirectory($previousCwd)
    Pop-Location
}

if ($NoInstall) {
    Write-Host ''
    Ok 'Fertig (nur gebaut, nichts installiert).'
    exit 0
}

# ─────────────────────────────────────────────────────────────────────────────
Step '5/5  In CATIA installieren'
# ─────────────────────────────────────────────────────────────────────────────
if (-not $PluginDir) {
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
        # Der Ordner mit der zuletzt geänderten JAR ist der, den du benutzt
        $PluginDir = $existing | Sort-Object { (Get-Item (Join-Path $_ $JarName)).LastWriteTime } -Descending | Select-Object -First 1
        if ($existing.Count -gt 1) {
            Info 'Mehrere Plugin-Ordner gefunden (genommen wird der mit der neuesten JAR):'
            $existing | ForEach-Object { Info "  - $_" }
        }
    }
}
if (-not $PluginDir) {
    Warn 'Das installierte Plugin wurde nicht gefunden.'
    Info "Suche im Explorer nach dem Ordner '$PluginId' (z. B. unter %LOCALAPPDATA%\.magic.systems.of.systems.architect\2026x\plugins)."
    $PluginDir = Ask "Pfad zum Ordner '$PluginId' einfügen (oder leer lassen zum Abbrechen)"
    if (-not $PluginDir) { Fail "Kein Plugin-Ordner. Die JAR liegt unter build\$JarName." }
    if ((Split-Path $PluginDir -Leaf) -ne $PluginId) { $PluginDir = Join-Path $PluginDir $PluginId }
}
New-Item -ItemType Directory -Force -Path $PluginDir | Out-Null
Ok "Plugin-Ordner: $PluginDir"

# Läuft CATIA noch? (Die JAR ist dann gesperrt)
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

$targetJar = Join-Path $PluginDir $JarName
try {
    if (Test-Path $targetJar) {
        Copy-Item $targetJar "$targetJar.bak" -Force
        Info "Alte JAR gesichert: $JarName.bak"
    }
    Copy-Item (Join-Path $Repo "build/$JarName") $targetJar -Force
    Copy-Item (Join-Path $Repo 'plugin.xml') (Join-Path $PluginDir 'plugin.xml') -Force

    $dx = Join-Path $PluginDir 'data_extraction'
    New-Item -ItemType Directory -Force -Path $dx | Out-Null
    foreach ($vm in @('sysml_flat_export.vm', 'sysml_partial_export.vm')) {
        Copy-Item (Join-Path $Repo "src/com/schroedingersblock/data_extraction/$vm") (Join-Path $dx $vm) -Force
    }

    $promptsDir = Join-Path $PluginDir 'prompts'
    New-Item -ItemType Directory -Force -Path $promptsDir | Out-Null
    $newPrompt = Join-Path $Repo 'installer/ressources/System_Prompt.md'
    $installedPrompt = Join-Path $promptsDir 'System_Prompt.md'
    if ((Test-Path $installedPrompt) -and ((Get-FileHash $installedPrompt).Hash -ne (Get-FileHash $newPrompt).Hash)) {
        Copy-Item $newPrompt "$installedPrompt.new" -Force
        Warn 'System_Prompt.md wurde von euch geändert und bleibt erhalten. Neue Version liegt daneben als System_Prompt.md.new.'
    } else {
        Copy-Item $newPrompt $installedPrompt -Force
    }
} catch {
    Fail "Kopieren fehlgeschlagen: $($_.Exception.Message)`n   Ist CATIA wirklich geschlossen?"
}

Write-Host ''
Ok 'Fertig! Das neue Plugin ist installiert.'
Info 'Jetzt CATIA starten -> Rechtsklick auf ein Element -> "Schrödinger''s Block" -> "Ask the model..."'
Info "Zurück zur alten Version: $JarName.bak im Plugin-Ordner wieder in $JarName umbenennen."
exit 0
