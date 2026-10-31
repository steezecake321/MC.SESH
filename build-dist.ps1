<#
Builds the distributable package:  dist\MC.SESH\  and  dist\MC.SESH-<version>-win64.zip
Needs a JDK 17+ (javac, jar, jlink). Usage:  powershell -ExecutionPolicy Bypass -File build-dist.ps1 [-Version 2.0.0] [-NoRuntime]
#>
param([string]$Version = '2.0.0', [switch]$NoRuntime)
$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot

# locate a JDK that has jlink
$jdk = $null
# Eclipse Temurin first: GPLv2 + Classpath Exception, so the bundled runtime can be redistributed freely
$cands = @(Get-ChildItem 'C:\Program Files\Eclipse Adoptium' -Directory -Filter 'jdk-25*' -ErrorAction SilentlyContinue | Sort-Object Name -Descending | ForEach-Object FullName) + @($env:JAVA_HOME) + @(Get-ChildItem 'C:\Program Files\Java' -Directory -Filter 'jdk*' -ErrorAction SilentlyContinue | Sort-Object Name -Descending | ForEach-Object FullName)
foreach ($c in $cands) { if ($c -and (Test-Path (Join-Path $c 'bin\jlink.exe'))) { $jdk = $c; break } }
if (-not $jdk) { throw 'No JDK with jlink found. Install a JDK (17 or newer) or set JAVA_HOME.' }
"Using JDK: $jdk"

$out = Join-Path $root 'dist\MC.SESH'
$work = Join-Path $root 'dist\build'
if (Test-Path $out) { Remove-Item -LiteralPath $out -Recurse -Force }
if (Test-Path $work) { Remove-Item -LiteralPath $work -Recurse -Force }
New-Item -ItemType Directory -Force "$work\classes", "$out\app", "$out\tools" | Out-Null

'Compiling...'
$srcs = Get-ChildItem (Join-Path $root 'src') -Filter *.java | ForEach-Object FullName
& "$jdk\bin\javac.exe" -Xlint:-options -encoding UTF-8 -d "$work\classes" $srcs
if ($LASTEXITCODE -ne 0) { throw 'Compile failed' }
Copy-Item (Join-Path $root 'web') "$work\classes\web" -Recurse
Set-Content -Path "$work\classes\version.txt" -Value $Version -Encoding ASCII -NoNewline

'Packing app\mcmanager.jar...'
& "$jdk\bin\jar.exe" --create --file "$out\app\mcmanager.jar" --main-class Main -C "$work\classes" .
if ($LASTEXITCODE -ne 0) { throw 'jar failed' }

if (-not $NoRuntime) {
    'Building bundled Java runtime (jlink)...'
    & "$jdk\bin\jlink.exe" --module-path "$jdk\jmods" --add-modules 'java.se,jdk.httpserver,jdk.unsupported,jdk.crypto.ec,jdk.crypto.mscapi,jdk.crypto.cryptoki,jdk.security.auth,jdk.zipfs,jdk.net,jdk.management,jdk.naming.dns,jdk.charsets' `
        --strip-debug --no-header-files --no-man-pages --compress zip-6 --output "$out\runtime"
    if ($LASTEXITCODE -ne 0) { throw 'jlink failed' }
    # jlink stamps every file with the build time, so an upgrade would try to overwrite a runtime that running
    # servers still have open (Install.bat then fails). Files whose content is identical to the installed runtime
    # keep the installed timestamp (the copy skips them); anything else gets one fixed date so builds stay stable.
    'Stabilising runtime timestamps...'
    $refRoot = 'C:\MC.SESH\runtime'
    $fixed = [datetime]'2026-01-01 00:00:00'
    $kept = 0; $total = 0
    foreach ($f in Get-ChildItem "$out\runtime" -Recurse -File) {
        $total++
        $stamp = $fixed
        $rel = $f.FullName.Substring("$out\runtime".Length)
        $ref = Get-Item -LiteralPath ($refRoot + $rel) -ErrorAction SilentlyContinue
        if ($ref -and $ref.Length -eq $f.Length -and (Get-FileHash $f.FullName -Algorithm SHA1).Hash -eq (Get-FileHash $ref.FullName -Algorithm SHA1).Hash) { $stamp = $ref.LastWriteTime; $kept++ }
        $f.LastWriteTime = $stamp
    }
    "  $kept of $total runtime files match the installed runtime"
}

Copy-Item (Join-Path $root 'tools\*.ps1') "$out\tools\"
Copy-Item (Join-Path $root 'brand\mcsesh.ico') $out
foreach ($f in 'Install.bat', 'Install-Text.bat', 'Start.bat', 'Stop.bat', 'Open.bat', 'Owner.bat', 'Setup.ps1', 'Uninstall.ps1', 'README.txt') { Copy-Item (Join-Path $root "packaging\$f") $out }

$zip = Join-Path $root "dist\MC.SESH-$Version-win64.zip"
if (Test-Path $zip) { Remove-Item -LiteralPath $zip -Force }
'Zipping...'
Compress-Archive -Path $out -DestinationPath $zip -CompressionLevel Optimal
$mb = [math]::Round((Get-Item $zip).Length / 1MB, 1)
"Done: $zip ($mb MB)"
"Unpacked size: " + [math]::Round(((Get-ChildItem $out -Recurse -File | Measure-Object Length -Sum).Sum / 1MB), 1) + ' MB'
