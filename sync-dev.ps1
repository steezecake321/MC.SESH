<#
Developer shortcut: pushes the current source into an UNZIPPED package folder without rebuilding the Java runtime or the zip.
Compiles, rebuilds app\mcmanager.jar (web UI inside), and copies tools\ and the packaging scripts.
Usage:  powershell -ExecutionPolicy Bypass -File sync-dev.ps1 [-Target <folder that contains app\ and Setup.ps1>] [-Version 2.0.5-dev]
#>
param(
    [string]$Target = (Join-Path $PSScriptRoot 'dist\MCServerManager-2.0.4-dev-win64\MCServerManager'),
    [string]$Version = 'dev'
)
$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
if (-not (Test-Path (Join-Path $Target 'app'))) { throw "Target '$Target' does not look like an unzipped package (no app folder)." }
$jdk = $null
foreach ($c in @($env:JAVA_HOME) + @(Get-ChildItem 'C:\Program Files\Java' -Directory -Filter 'jdk*' -ErrorAction SilentlyContinue | Sort-Object Name -Descending | ForEach-Object FullName)) {
    if ($c -and (Test-Path (Join-Path $c 'bin\jar.exe'))) { $jdk = $c; break }
}
if (-not $jdk) { throw 'No JDK found.' }
$work = Join-Path $env:TEMP 'mcm-sync-build'
if (Test-Path $work) { [IO.Directory]::Delete($work, $true) }
New-Item -ItemType Directory -Force "$work\classes" | Out-Null
$srcs = Get-ChildItem (Join-Path $root 'src') -Filter *.java | ForEach-Object FullName
& "$jdk\bin\javac.exe" -Xlint:-options -encoding UTF-8 -d "$work\classes" $srcs
if ($LASTEXITCODE -ne 0) { throw 'Compile failed' }
Copy-Item (Join-Path $root 'web') "$work\classes\web" -Recurse
Set-Content -Path "$work\classes\version.txt" -Value $Version -Encoding ASCII -NoNewline
$jar = Join-Path $Target 'app\mcmanager.jar'
& "$jdk\bin\jar.exe" --create --file $jar --main-class Main -C "$work\classes" .
if ($LASTEXITCODE -ne 0) { throw 'jar failed (is the manager running from this folder? run Stop.bat first)' }
New-Item -ItemType Directory -Force (Join-Path $Target 'tools') | Out-Null
Copy-Item (Join-Path $root 'tools\*.ps1') (Join-Path $Target 'tools') -Force
foreach ($f in 'Install.bat', 'Start.bat', 'Stop.bat', 'Open.bat', 'Owner.bat', 'Setup.ps1', 'Uninstall.ps1', 'README.txt') { Copy-Item (Join-Path $root "packaging\$f") $Target -Force }
[IO.Directory]::Delete($work, $true)
"Synced to $Target (version $Version)"
