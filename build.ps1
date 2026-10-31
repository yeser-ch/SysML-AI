<#
  SysML AI Q&A (Weiterentwicklung von Schrödinger's Block) – Plugin bauen und in CATIA Magic installieren
  ------------------------------------------------------------------
  Einfach build.bat doppelklicken. Das Skript
    1. findet die CATIA-Installation (für die Bibliotheken),
    2. findet ein Java JDK (javac),
    3. kompiliert den Code in src\ zu build\schroedingers-block.jar,
    4. legt ein Installationspaket an: dist\SysML-AI-QA_v<Version>.zip
       (für neue Nutzer: entpacken, install.bat doppelklicken – ohne Java, ohne Git),
    5. installiert das Paket mit install.ps1 in CATIA
       (die alte JAR wird als .bak gesichert, ein geänderter System-Prompt bleibt erhalten).

  Optionen (nur falls die automatische Suche nicht klappt):
    build.bat -CatiaDir "C:\Program Files\...\CATIA Magic ... 2026x"
    build.bat -PluginDir "C:\Program Files\Magic Systems of Systems Architect\plugins\com.schroedingersblock.plugin"
    build.bat -JdkDir "C:\Program Files\Eclipse Adoptium\jdk-21..."
    build.bat -NoInstall      (nur bauen und Paket erstellen, nichts installieren)
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
Step '1/6  CATIA-Installation suchen'
# ─────────────────────────────────────────────────────────────────────────────
# CATIA Magic / Magic Systems of Systems Architect / Cameo: Hauptordner enthält 'bin' und 'lib' (mit .jar-Dateien)
function Test-CatiaDir($d) {
    if (-not $d -or -not (Test-Path $d)) { return $false }
    if ((Test-Path (Join-Path $d 'lib/md.jar')) -or (Test-Path (Join-Path $d 'lib/md_api.jar'))) { return $true }
    $lib = Join-Path $d 'lib'
    # 'plugins' unterscheidet CATIA von anderen Java-Programmen (z. B. einem JDK, das auch bin + lib hat)
    if (-not (Test-Path (Join-Path $d 'bin')) -or -not (Test-Path $lib) -or -not (Test-Path (Join-Path $d 'plugins'))) { return $false }
    return [bool](Get-ChildItem -LiteralPath $lib -Recurse -Filter '*.jar' -File -ErrorAction SilentlyContinue | Select-Object -First 1)
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
                                              @{ Expression = { ($_ -match 'Magic|CATIA|Cameo|Architect') -as [int] }; Descending = $true } |
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
    # Falls jemand den Pfad zur .exe oder zum bin-Ordner einfügt: auf den Hauptordner zurückgehen
    if ($CatiaDir -match '\.exe$') { $CatiaDir = Split-Path $CatiaDir -Parent }
    if ((Split-Path $CatiaDir -Leaf) -eq 'bin') { $CatiaDir = Split-Path $CatiaDir -Parent }
    if (-not (Test-CatiaDir $CatiaDir)) { Fail "Im Ordner '$CatiaDir' gibt es keine CATIA-Bibliotheken (Ordner 'lib' mit .jar-Dateien)." }
}
Ok "CATIA: $CatiaDir"

# ─────────────────────────────────────────────────────────────────────────────
Step '2/6  Java JDK suchen (javac, mindestens Version 17)'
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
Step '3/6  Code kompilieren'
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
    Step '4/6  JAR erstellen'
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

# ─────────────────────────────────────────────────────────────────────────────
Step '5/6  Installationspaket erstellen'
# ─────────────────────────────────────────────────────────────────────────────
$version = 'dev'
$verSrc = Join-Path $Repo 'src/com/schroedingersblock/api/EvaluationRecord.java'
if ((Test-Path $verSrc) -and ((Get-Content $verSrc -Raw) -match 'PLUGIN_VERSION\s*=\s*"([^"]+)"')) { $version = $Matches[1] }
$pkgName = "SysML-AI-QA_v$version"
$pkgRoot = Join-Path $Repo 'build/package'
$pkgDir = Join-Path $pkgRoot $pkgName
$plug = Join-Path $pkgDir 'plugin'
try {
    if (Test-Path $pkgRoot) { Remove-Item $pkgRoot -Recurse -Force }
    New-Item -ItemType Directory -Force -Path (Join-Path $plug 'data_extraction') | Out-Null
    New-Item -ItemType Directory -Force -Path (Join-Path $plug 'prompts') | Out-Null
    Copy-Item (Join-Path $Repo "build/$JarName") (Join-Path $plug $JarName) -Force
    Copy-Item (Join-Path $Repo 'plugin.xml') (Join-Path $plug 'plugin.xml') -Force
    foreach ($vm in @('sysml_flat_export.vm', 'sysml_partial_export.vm')) {
        Copy-Item (Join-Path $Repo "src/com/schroedingersblock/data_extraction/$vm") (Join-Path $plug "data_extraction/$vm") -Force
    }
    Copy-Item (Join-Path $Repo 'installer/ressources/System_Prompt.md') (Join-Path $plug 'prompts/System_Prompt.md') -Force
    [System.IO.File]::WriteAllText((Join-Path $plug 'VERSION.txt'), $version)
    foreach ($f in @('install.bat', 'install.ps1', 'INSTALL.md')) {
        Copy-Item (Join-Path $Repo $f) (Join-Path $pkgDir $f) -Force
    }
} catch {
    Fail "Installationspaket konnte nicht zusammengestellt werden: $($_.Exception.Message)"
}

# ZIP für die Weitergabe (ein Fehler hier verhindert die Installation nicht)
try {
    $distDir = Join-Path $Repo 'dist'
    New-Item -ItemType Directory -Force -Path $distDir | Out-Null
    $zipPath = Join-Path $distDir "$pkgName.zip"
    if (Test-Path $zipPath) { Remove-Item $zipPath -Force }
    Add-Type -AssemblyName System.IO.Compression
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    [System.IO.Compression.ZipFile]::CreateFromDirectory($pkgDir, $zipPath, [System.IO.Compression.CompressionLevel]::Optimal, $true)
    Ok ("Paket: $zipPath  ({0:N0} KB)" -f ((Get-Item $zipPath).Length / 1KB))
    Info 'Diese ZIP an neue Nutzer weitergeben (z. B. GitHub -> Releases). Sie brauchen kein Java und kein Git.'
} catch {
    Warn "ZIP konnte nicht erstellt werden: $($_.Exception.Message)"
}

if ($NoInstall) {
    Write-Host ''
    Ok 'Fertig (gebaut und Paket erstellt, nichts installiert).'
    exit 0
}

# ─────────────────────────────────────────────────────────────────────────────
Step '6/6  In CATIA installieren (install.ps1)'
# ─────────────────────────────────────────────────────────────────────────────
$installArgs = @{ CatiaDir = $CatiaDir; SourceDir = $plug }
if ($PluginDir) { $installArgs.PluginDir = $PluginDir }
if ($NonInteractive) { $installArgs.NonInteractive = $true }
& (Join-Path $Repo 'install.ps1') @installArgs
exit $LASTEXITCODE
