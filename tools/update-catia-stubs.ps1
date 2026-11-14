<#
  CATIA-Stubs für den automatischen Build auf GitHub aktualisieren.
  ------------------------------------------------------------------
  GitHub hat kein CATIA. Damit GitHub das Plugin trotzdem bei jedem Push bauen und als
  neuestes Release veröffentlichen kann, liegen im Ordner ci\catia-stubs die Signaturen der
  CATIA-Klassen, die das Plugin verwendet (ohne Programmcode von CATIA).

  Nur nötig, wenn der Plugin-Code CATIA-Klassen oder -Methoden verwendet, die bisher nicht
  verwendet wurden. Dann schlägt der Build auf GitHub fehl und meldet das.

  Einfach update-catia-stubs.bat doppelklicken. Braucht CATIA und ein Java JDK 17+.
  Optionen:  update-catia-stubs.bat -CatiaDir "C:\Program Files\Magic Systems of Systems Architect"
             update-catia-stubs.bat -JdkDir "C:\Program Files\Eclipse Adoptium\jdk-21..."
#>
param(
    [string]$CatiaDir = "",
    [string]$JdkDir = ""
)
$ErrorActionPreference = 'Stop'
$Repo = Split-Path $PSScriptRoot -Parent
$OnWindows = ($env:OS -eq 'Windows_NT')
$ExeSuffix = if ($OnWindows) { '.exe' } else { '' }

function Step($m) { Write-Host ''; Write-Host "== $m" -ForegroundColor Cyan }
function Info($m) { Write-Host "   $m" }
function Ok($m)   { Write-Host "   $m" -ForegroundColor Green }
function Warn($m) { Write-Host "   $m" -ForegroundColor Yellow }
function Fail($m) { Write-Host ''; Write-Host "FEHLER: $m" -ForegroundColor Red; exit 1 }

if (-not (Test-Path (Join-Path $Repo 'src'))) { Fail "Das Skript muss im Ordner tools des Repositorys liegen." }

Step 'CATIA-Installation suchen'
function Test-CatiaDir($d) {
    if (-not $d -or -not (Test-Path $d)) { return $false }
    return ((Test-Path (Join-Path $d 'lib/md.jar')) -or (Test-Path (Join-Path $d 'lib/md_api.jar')))
}
if ($CatiaDir -and -not (Test-CatiaDir $CatiaDir)) { Fail "Im Ordner '$CatiaDir' gibt es kein lib\md.jar." }
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
    $CatiaDir = @($candidates | Select-Object -Unique) |
        Sort-Object @{ Expression = { ($_ -match '2026') -as [int] }; Descending = $true } | Select-Object -First 1
}
if (-not $CatiaDir) {
    $CatiaDir = (Read-Host '   CATIA nicht gefunden. Pfad zum CATIA-Ordner (mit bin und lib) einfügen').Trim().Trim('"')
    if (-not (Test-CatiaDir $CatiaDir)) { Fail "Im Ordner '$CatiaDir' gibt es kein lib\md.jar." }
}
Ok "CATIA: $CatiaDir"

Step 'Java JDK suchen (mindestens Version 17)'
$jdks = New-Object System.Collections.Generic.List[string]
if ($JdkDir) { $jdks.Add($JdkDir) }
if ($env:JAVA_HOME) { $jdks.Add($env:JAVA_HOME) }
$cmd = Get-Command ("javac" + $ExeSuffix) -ErrorAction SilentlyContinue
if ($cmd) { $jdks.Add((Split-Path (Split-Path $cmd.Source -Parent) -Parent)) }
foreach ($r in @('C:\Program Files\Eclipse Adoptium', 'C:\Program Files\Java', 'C:\Program Files\Microsoft',
                 'C:\Program Files\Amazon Corretto', 'C:\Program Files\Zulu', 'C:\Program Files\BellSoft', (Join-Path $HOME '.jdks'))) {
    if (Test-Path $r) { Get-ChildItem -LiteralPath $r -Directory -ErrorAction SilentlyContinue | ForEach-Object { $jdks.Add($_.FullName) } }
}
$Java = $null; $best = 0
foreach ($j in ($jdks | Select-Object -Unique)) {
    $javac = Join-Path $j ("bin/javac" + $ExeSuffix)
    $java = Join-Path $j ("bin/java" + $ExeSuffix)
    if (-not (Test-Path $javac) -or -not (Test-Path $java)) { continue }
    $v = (& $javac -version 2>&1 | Out-String)
    if ($v -match 'javac\s+(\d+)') { $m = [int]$Matches[1]; if ($m -ge 17 -and $m -gt $best) { $best = $m; $Java = $java } }
}
if (-not $Java) { Fail "Kein Java JDK 17+ gefunden (siehe INSTALL.md, Weg B)." }
Ok "Java: $Java"

Step 'Stubs erzeugen und prüfen'
$out = Join-Path $Repo 'ci/catia-stubs'
$work = Join-Path ([System.IO.Path]::GetTempPath()) ('sysml-ai-stubs-' + [guid]::NewGuid().ToString('N').Substring(0, 8))
$old = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
& $Java (Join-Path $PSScriptRoot 'stubs/StubGen.java') $CatiaDir (Join-Path $Repo 'src') $out $work 2>&1 | ForEach-Object { "$_" } |
    Where-Object { $_ -notmatch '^Picked up JAVA_TOOL_OPTIONS' } | ForEach-Object { Write-Host $_ }
$code = $LASTEXITCODE
$ErrorActionPreference = $old
Remove-Item -LiteralPath $work -Recurse -Force -ErrorAction SilentlyContinue
if ($code -eq 1) { Fail 'Stubs konnten nicht erzeugt werden (Meldungen oben).' }

Step 'Nächster Schritt'
$git = Get-Command git -ErrorAction SilentlyContinue
$isRepo = $git -and (Test-Path (Join-Path $Repo '.git'))
if ($isRepo) {
    $answer = (Read-Host '   Ordner ci\catia-stubs jetzt committen und pushen? (j/n)').Trim()
    if ($answer -match '^[jJyY]') {
        & git -C $Repo add -- ci/catia-stubs
        & git -C $Repo commit -m 'Update CATIA API stubs for the GitHub build' -- ci/catia-stubs
        & git -C $Repo push
        if ($LASTEXITCODE -eq 0) { Ok 'Gepusht. GitHub baut jetzt das Release (Reiter Actions).' } else { Warn 'Push fehlgeschlagen, bitte von Hand pushen.' }
        exit 0
    }
}
Info 'Den Ordner ci\catia-stubs committen und pushen (z. B. mit GitHub Desktop).'
Info 'Danach baut GitHub das Plugin und aktualisiert das neueste Release automatisch.'
