<#
MC.SESH - setup
Run from the extracted folder:   powershell -ExecutionPolicy Bypass -File Setup.ps1
Everything is optional; you can also just run Start.bat from this folder without installing anything (portable).

Folder layout it creates:
  <InstallDir>\app\          program files
  <InstallDir>\runtime\      bundled Java
  <InstallDir>\tools\        helper scripts
  <InstallDir>\data\         settings, caches and logs
  <ServersDir>\              where new servers are created (you choose; default: <drive with the most free space>:\MC.Servers)
Servers can live anywhere; you can pick a different folder each time you create one.

Parameters (for unattended installs):  -InstallDir  -ServersDir  -Port  -Shortcuts  -Firewall  -Startup off|logon|boot  -NoLaunch  -Silent
You can change the startup behavior any time later in the app: Settings > Start with Windows.
#>
param(
    [string]$InstallDir,
    [string]$ServersDir,
    [int]$Port = 0,
    [switch]$Shortcuts,
    [switch]$Firewall,
    [ValidateSet('off', 'logon', 'boot')][string]$Startup,
    [switch]$NoLaunch,
    [switch]$Silent
)
$ErrorActionPreference = 'Stop'
$src = Split-Path -Parent $MyInvocation.MyCommand.Path
$isAdmin = ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)

function Ask($prompt, $default) {
    if ($Silent) { return $default }
    $r = Read-Host "$prompt [$default]"
    if ([string]::IsNullOrWhiteSpace($r)) { return $default }
    return $r.Trim().Trim('"')
}
function YesNo($prompt, $default) {
    if ($Silent) { return $false }
    $d = if ($default) { 'Y/n' } else { 'y/N' }
    $r = Read-Host "$prompt [$d]"
    if ([string]::IsNullOrWhiteSpace($r)) { return $default }
    return $r -match '^(y|yes)$'
}

Write-Host ''
Write-Host 'MC.SESH setup' -ForegroundColor Green
Write-Host ''
function AskText($title, $default, $example, $validate) {
    if ($Silent) { return $default }
    while ($true) {
        Write-Host ''
        Write-Host $title -ForegroundColor Cyan
        Write-Host "    Press Enter to use:   $default"
        Write-Host "    Or type your own, e.g. $example"
        $r = Read-Host '    >'
        $v = if ([string]::IsNullOrWhiteSpace($r)) { $default } else { $r.Trim().Trim('"') }
        $problem = & $validate $v
        if (-not $problem) { return $v }
        Write-Host "    $problem" -ForegroundColor Yellow
    }
}
$checkFolder = {
    param($v)
    try {
        if (-not [IO.Path]::IsPathRooted($v)) { return 'Please give a full path, such as D:\Apps\MC.SESH' }
        $full = [IO.Path]::GetFullPath($v)
        $drive = [IO.Path]::GetPathRoot($full)
        if (-not (Test-Path -LiteralPath $drive)) { return "The drive $drive does not exist." }
        return $null
    } catch { return 'That is not a valid folder path.' }
}
$checkPort = { param($v) if ($v -match '^\d+$' -and [int]$v -ge 1024 -and [int]$v -le 65535) { return $null } else { return 'Enter a number between 1024 and 65535.' } }

if (-not $Silent) {
    Write-Host 'For every question: press Enter to accept the suggested answer, or type your own and press Enter.'
}
if (-not $InstallDir) { $InstallDir = AskText '1) Where should the app be installed?' 'C:\MC.SESH' 'D:\Apps\MC.SESH' $checkFolder }
$InstallDir = [IO.Path]::GetFullPath($InstallDir)
# new servers go on the drive with the most free space by default (worlds and backups get big)
$bestDrive = Get-CimInstance Win32_LogicalDisk -Filter 'DriveType=3' -ErrorAction SilentlyContinue | Sort-Object FreeSpace -Descending | Select-Object -First 1
$defaultServers = if ($bestDrive) { "$($bestDrive.DeviceID)\MC.Servers" } else { Join-Path $InstallDir 'servers' }
if (-not $ServersDir) { $ServersDir = AskText '2) Where should NEW Minecraft servers be created by default? (you can pick another place each time)' $defaultServers 'D:\Minecraft\Servers' $checkFolder }
$ServersDir = [IO.Path]::GetFullPath($ServersDir)
if ($Port -le 0) { $Port = [int](AskText '3) Which port should the web page use?' '8585' '8600' $checkPort) }
if (-not $PSBoundParameters.ContainsKey('Shortcuts')) { $Shortcuts = YesNo 'Create Start Menu and Desktop shortcuts?' $true }
if (-not $PSBoundParameters.ContainsKey('Firewall')) {
    $Firewall = YesNo "Allow other devices on your home network to reach it (Windows Firewall rule for port $Port)?" $true
}
if (-not $Startup) {
    $Startup = 'off'
    if (-not $Silent) {
        Write-Host ''
        Write-Host 'Start the manager automatically?'
        Write-Host '  1) No'
        Write-Host '  2) When I sign in to Windows'
        Write-Host '  3) When the PC starts, before anyone signs in (servers come back after a reboot; asks for your Windows password once)'
        $c = Ask 'Choose 1, 2 or 3' '1'
        $Startup = switch ($c) { '2' { 'logon' } '3' { 'boot' } default { 'off' } }
    }
}
if (($Firewall -or $Startup -ne 'off') -and -not $isAdmin) { Write-Host 'Windows will ask you to approve the next step (Administrator rights).' -ForegroundColor Yellow }

# 1. copy files (skip when installing in place)
if ($src.TrimEnd('\') -ne $InstallDir.TrimEnd('\')) {
    $stopper = Join-Path $src 'tools\Stop-Manager.ps1'
    if ((Test-Path $stopper) -and (Test-Path $InstallDir)) {
        Write-Host 'Stopping the running manager ...'
        & powershell.exe -NoProfile -ExecutionPolicy Bypass -File $stopper -InstallDir $InstallDir | ForEach-Object { Write-Host "  $_" }
        if ($LASTEXITCODE -ne 0) { throw 'The running manager could not be stopped, so its files cannot be replaced. Close it (or approve the Windows prompt) and run Install.bat again.' }
        Start-Sleep -Seconds 1
    }
    Write-Host "Copying files to $InstallDir ..."
    New-Item -ItemType Directory -Force $InstallDir | Out-Null
    # Minecraft servers run on the bundled Java, which Windows locks while they run. Replacing only some of its files would break it,
    # so the runtime is updated only when nothing holds it; otherwise it is left exactly as it is (it updates on a later install).
    $xd = @()
    $rt = Join-Path $InstallDir 'runtime'
    $locked = $false
    foreach ($f in 'bin\java.exe', 'bin\server\jvm.dll', 'lib\modules') {
        $p = Join-Path $rt $f
        if (Test-Path -LiteralPath $p) { try { $fs = [IO.File]::Open($p, 'Open', 'ReadWrite', 'None'); $fs.Close() } catch { $locked = $true } }
    }
    if ($locked) {
        Write-Host 'The Java runtime is in use by running Minecraft servers, so it was left as it is. It updates the next time you install while they are stopped.'
        $xd = @('/XD', (Join-Path $src 'runtime'))
    }
    $rcOut = robocopy $src $InstallDir /E /XF Setup.ps1 Install.bat @xd /R:1 /W:1 /NFL /NDL /NJH /NJS /NP
    if ($LASTEXITCODE -ge 8) {
        $rcOut | Where-Object { $_ -match 'ERROR' } | Select-Object -First 8 | ForEach-Object { Write-Host "  $_" }
        throw "Copy failed (robocopy exit code $LASTEXITCODE). Files in $InstallDir are probably in use, often by a Minecraft server that runs on the bundled Java. Stop those servers (or close what is using them) and run Install.bat again."
    }
    Copy-Item (Join-Path $src 'Setup.ps1') $InstallDir -Force
}
New-Item -ItemType Directory -Force (Join-Path $InstallDir 'data') | Out-Null
New-Item -ItemType Directory -Force $ServersDir | Out-Null

# 2. settings (only on a fresh install; keeps existing servers on upgrade)
$cfgPath = Join-Path $InstallDir 'data\config.json'
if (-not (Test-Path $cfgPath)) {
    $cfg = [ordered]@{ bind = '0.0.0.0'; port = $Port; paths = [ordered]@{ serversRoot = $ServersDir }; servers = @() }
    [IO.File]::WriteAllText($cfgPath, ($cfg | ConvertTo-Json -Depth 5), (New-Object Text.UTF8Encoding($false)))
} else { Write-Host 'Existing settings kept.' }
[IO.File]::WriteAllText((Join-Path $InstallDir 'data\port.txt'), "$Port", (New-Object Text.ASCIIEncoding))

# 3. shortcuts
if ($Shortcuts) {
    $sh = New-Object -ComObject WScript.Shell
    $edge = "${env:ProgramFiles(x86)}\Microsoft\Edge\Application\msedge.exe"
    $targets = @([Environment]::GetFolderPath('Desktop'), (Join-Path ([Environment]::GetFolderPath('Programs')) ''))
    foreach ($dir in $targets) {
        $l = $sh.CreateShortcut((Join-Path $dir 'MC.SESH.lnk'))
        $l.TargetPath = (Join-Path $InstallDir 'Start.bat')
        $l.WorkingDirectory = $InstallDir
        $l.WindowStyle = 7
        $ico = Join-Path $InstallDir 'mcsesh.ico'; $l.IconLocation = if (Test-Path -LiteralPath $ico) { $ico } else { "$env:SystemRoot\System32\shell32.dll,13" }
        $l.Description = 'Start MC.SESH and open it in your browser'
        $l.Save()
    }
    Write-Host 'Shortcuts created (Desktop and Start Menu).'
}

# 4. firewall + startup (needs Administrator; the helper asks Windows for approval if this window is not elevated)
$helper = Join-Path $InstallDir 'tools\Set-Startup.ps1'
function Run-Helper($helperArgs) {
    $base = @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', "`"$helper`"") + $helperArgs
    if ($isAdmin) { & powershell.exe @base -NoPause }
    else {
        if ($Silent) { $base += '-NoPause' }   # nobody is watching the elevated window in an unattended or browser-driven setup
        Start-Process -FilePath powershell.exe -Verb RunAs -Wait -ArgumentList ($base -join ' ')
    }
}
$common = @('-InstallDir', "`"$InstallDir`"", '-Port', "$Port")
if ($Firewall) { Run-Helper (@('-Action', 'firewall') + $common) }
# unattended boot-time startup runs as the system account, so no Windows password is needed (same as the installer wizard)
if ($Startup -ne 'off') { Run-Helper (@('-Action', $Startup) + $(if ($Silent -and $Startup -eq 'boot') { @('-AsSystem') } else { @() }) + $common) }

Write-Host ''
Write-Host "Installed to $InstallDir" -ForegroundColor Green
Write-Host "New servers go in $ServersDir by default."
Write-Host 'The first time you open the manager (in a browser on this PC) you create the owner account: the manager administrator (OP5).'
Write-Host 'Other people get their own logins from invites; Owner.bat in the install folder resets passwords if anyone is ever locked out.'
if (-not $NoLaunch) {
    if (YesNo 'Start the manager now?' $true) { Start-Process -FilePath (Join-Path $InstallDir 'Start.bat') -WorkingDirectory $InstallDir -WindowStyle Hidden }
}
