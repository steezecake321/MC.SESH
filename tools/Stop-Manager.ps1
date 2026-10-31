<#
Stops the MC Server Manager (never the Minecraft servers). Finds it by its command line and by the port it listens on, because a
manager started by the boot task runs with administrator rights and hides its command line from a normal window. If a normal
stop is refused, Windows is asked to approve a short administrator step.
#>
param(
    [string]$InstallDir = (Split-Path -Parent $PSScriptRoot),
    [int]$Port = 0,
    [switch]$NoElevate
)
$ErrorActionPreference = 'Continue'
$InstallDir = [IO.Path]::GetFullPath($InstallDir).TrimEnd('\')
if ($Port -le 0) {
    $Port = 8585
    $pf = Join-Path $InstallDir 'data\port.txt'
    if (Test-Path $pf) { $n = 0; if ([int]::TryParse((Get-Content $pf -Raw).Trim(), [ref]$n) -and $n -gt 0) { $Port = $n } }
}

function Get-ManagerPids {
    $ids = New-Object System.Collections.Generic.HashSet[int]
    Get-CimInstance Win32_Process -Filter "Name='javaw.exe' OR Name='java.exe'" -ErrorAction SilentlyContinue | ForEach-Object {
        if ($_.CommandLine -like '*mcmanager.jar*' -and $_.CommandLine -like ('*' + $InstallDir + '*')) { [void]$ids.Add([int]$_.ProcessId) }
    }
    Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue | ForEach-Object {
        $p = Get-Process -Id $_.OwningProcess -ErrorAction SilentlyContinue
        if ($p -and $p.ProcessName -match '^javaw?$') { [void]$ids.Add([int]$p.Id) }
    }
    return @($ids)
}

$pids = Get-ManagerPids
if (-not $pids.Count) { 'MC.SESH is not running.'; exit 0 }

# 1. Ask it to close itself (works even when it runs with administrator rights, no prompt needed)
$tokenFile = Join-Path $InstallDir 'data\shutdown.token'
if (Test-Path -LiteralPath $tokenFile) {
    try {
        $tok = (Get-Content -LiteralPath $tokenFile -Raw).Trim()
        Invoke-WebRequest -Uri "http://127.0.0.1:$Port/api/shutdown" -Method POST -Headers @{ 'X-Requested-With' = 'mgr'; 'X-Shutdown-Token' = $tok } -UseBasicParsing -TimeoutSec 5 | Out-Null
        for ($i = 0; $i -lt 20; $i++) {
            Start-Sleep -Milliseconds 400
            if (-not @($pids | Where-Object { Get-Process -Id $_ -ErrorAction SilentlyContinue }).Count) { break }
        }
        if (-not @($pids | Where-Object { Get-Process -Id $_ -ErrorAction SilentlyContinue }).Count) { 'The manager closed itself. MC.SESH stopped.'; exit 0 }
    } catch { }
}
foreach ($id in $pids) { Stop-Process -Id $id -Force -ErrorAction SilentlyContinue }
Start-Sleep -Seconds 2
$left = @($pids | Where-Object { Get-Process -Id $_ -ErrorAction SilentlyContinue })
if ($left.Count -and -not $NoElevate) {
    'The manager is running with administrator rights (it was started by the Windows startup task). Windows will ask you to approve stopping it.'
    try {
        Start-Process -FilePath powershell.exe -Verb RunAs -Wait -WindowStyle Hidden -ArgumentList @('-NoProfile', '-Command', ('Stop-Process -Id ' + ($left -join ',') + ' -Force'))
    } catch { 'Approval was declined or failed: ' + $_.Exception.Message }
    Start-Sleep -Seconds 2
    $left = @($left | Where-Object { Get-Process -Id $_ -ErrorAction SilentlyContinue })
}
if ($left.Count) { 'Could not stop the manager (process ' + ($left -join ', ') + '). End it from Task Manager as administrator, then try again.'; exit 1 }
'MC.SESH stopped.'
exit 0
