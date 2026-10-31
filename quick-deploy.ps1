<#
Quick build for everyday changes: recompiles the Java, repacks only app\mcmanager.jar (no jlink runtime, no zip), syntax-checks every
web\*.js file in headless Edge, and with -Push stops the installed manager, copies the new jar/tools/README into C:\MC.SESH (the MC.SESH install),
starts the manager again through its startup task and verifies it. Minecraft servers keep running.
The JS check needs a browser look (see the printed address); run again with -Push -SkipJsCheck once it says ALL OK.
Use build-dist.ps1 only when you need a shippable zip (or the Java runtime / Install scripts changed).
Usage:  powershell -ExecutionPolicy Bypass -File quick-deploy.ps1 [-Version 2.10.3] [-Push] [-SkipJsCheck]
#>
param([string]$Version = '', [switch]$Push, [switch]$SkipJsCheck, [string]$Install = 'C:\MC.SESH')
$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
if (-not $Version) {
    $z = Get-ChildItem (Join-Path $root 'dist') -Filter 'MC.SESH-*-win64.zip' | Sort-Object LastWriteTime -Descending | Select-Object -First 1
    $Version = if ($z -and $z.Name -match 'MC\.SESH-(.+)-win64') { $Matches[1] } else { 'dev' }
}
$jdk = $null
foreach ($c in @($env:JAVA_HOME) + @(Get-ChildItem 'C:\Program Files\Java' -Directory -Filter 'jdk*' -ErrorAction SilentlyContinue | Sort-Object Name -Descending | ForEach-Object FullName)) {
    if ($c -and (Test-Path (Join-Path $c 'bin\javac.exe'))) { $jdk = $c; break }
}
if (-not $jdk) { throw 'No JDK found.' }

$work = Join-Path $root 'dist\quick'
if (Test-Path $work) { Remove-Item -LiteralPath $work -Recurse -Force }
New-Item -ItemType Directory -Force "$work\classes" | Out-Null

'Compiling...'
$srcs = Get-ChildItem (Join-Path $root 'src') -Filter *.java | ForEach-Object FullName
& "$jdk\bin\javac.exe" -Xlint:-options -encoding UTF-8 -d "$work\classes" $srcs
if ($LASTEXITCODE -ne 0) { throw 'Compile failed' }
Copy-Item (Join-Path $root 'web') "$work\classes\web" -Recurse
Set-Content -Path "$work\classes\version.txt" -Value $Version -Encoding ASCII -NoNewline
& "$jdk\bin\jar.exe" --create --file "$work\mcmanager.jar" --main-class Main -C "$work\classes" .
if ($LASTEXITCODE -ne 0) { throw 'jar failed' }
"Built $work\mcmanager.jar (version $Version)"

if (-not $SkipJsCheck) {
    # No JavaScript engine ships with Windows or the JDK, so the check runs in a browser: this writes a page that tries to parse every
    # web\*.js file and starts a tiny local web server for it. Open the printed address; it must say ALL OK. Stop the server afterwards.
    $files = [ordered]@{}
    foreach ($f in Get-ChildItem (Join-Path $root 'web') -Filter *.js) { $files[$f.Name] = [IO.File]::ReadAllText($f.FullName) }
    $json = ($files | ConvertTo-Json -Compress -Depth 3).Replace('</', '<\/')
    $dir = Join-Path $work 'jscheck'
    New-Item -ItemType Directory -Force $dir | Out-Null
    $page = "<!doctype html><meta charset=utf-8><pre id=o>running</pre><script>var F=$json;var R=[],bad=0;Object.keys(F).forEach(function(k){try{new Function(F[k]);R.push(k+' OK')}catch(e){bad++;R.push(k+' ERR '+e.message)}});document.getElementById('o').textContent=(bad?'JS ERRORS':'ALL OK')+' ('+R.length+' files)\n'+R.join('\n');</script>"
    [IO.File]::WriteAllText("$dir\t.html", $page, (New-Object Text.UTF8Encoding $false))
    Get-Process jwebserver -ErrorAction SilentlyContinue | Stop-Process -Force
    Start-Process "$jdk\bin\jwebserver.exe" -ArgumentList '-p', '8597', '-d', ('"' + $dir + '"'), '-b', '127.0.0.1' -WindowStyle Hidden | Out-Null
    'JS check page: http://127.0.0.1:8597/t.html  (must say ALL OK; then stop it:  Get-Process jwebserver | Stop-Process)'
}
if ($Push) {
    "Stopping the installed manager..."
    cmd /c """$Install\Stop.bat"" >nul 2>nul"
    for ($i = 0; $i -lt 20 -and (Get-NetTCPConnection -State Listen -LocalPort 8585 -ErrorAction SilentlyContinue); $i++) { Start-Sleep 1 }
    Copy-Item "$work\mcmanager.jar" "$Install\app\mcmanager.jar" -Force
    Copy-Item (Join-Path $root 'tools\*.ps1') "$Install\tools\" -Force
    foreach ($f in 'Start.bat', 'Stop.bat', 'Open.bat', 'Owner.bat', 'Setup.ps1', 'Uninstall.ps1', 'README.txt') { Copy-Item (Join-Path $root "packaging\$f") $Install -Force }
    $taskName = if (Get-ScheduledTask -TaskName 'MC.SESH' -ErrorAction SilentlyContinue) { 'MC.SESH' } else { 'MC Server Manager' }
    schtasks /run /tn $taskName | Out-Null
    for ($i = 0; $i -lt 40 -and -not (Get-NetTCPConnection -State Listen -LocalPort 8585 -ErrorAction SilentlyContinue); $i++) { Start-Sleep 1 }
    if (-not (Get-NetTCPConnection -State Listen -LocalPort 8585 -ErrorAction SilentlyContinue)) { throw 'The manager did not come back on port 8585. Start it with Start.bat.' }
    "Manager is back up with version $Version."
}
