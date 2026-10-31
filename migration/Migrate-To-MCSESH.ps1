<#
Moves an existing "MC Server Manager" installation (default C:\MCServerManager) to the new MC.SESH installation (default C:\MC.SESH).
Run it AFTER the MC.SESH installer has put the new copy in place, and run it as Administrator (Migrate-To-MCSESH.bat does that for you).

What it does, in order (nothing is deleted):
  1. checks both folders, lists your servers and what will happen, and asks you to type YES
  2. stops the OLD MANAGER only (never the Minecraft servers: they keep running and nobody is disconnected)
  3. makes a safety copy of the old data folder
  4. copies your settings, accounts, notifications and caches into the new install
  5. moves the old backups folder into the new install (an instant rename on the same drive)
  6. replaces the old startup task and firewall rule ("MC Server Manager") with new ones called "MC.SESH" (asks for your Windows password once)
  7. removes the old shortcuts and starts the new manager, then checks it came up
If a step fails before the old startup task is removed, the old manager is started again.
The old folder is left exactly as it is. Servers that live inside it (the script lists them) must stay there; never delete the old folder while it contains one.
#>
param(
    [string]$OldDir = 'C:\MCServerManager',
    [string]$NewDir = 'C:\MC.SESH',
    [switch]$NoSystemChanges,   # testing only: skips stopping the manager, tasks, firewall, shortcuts and starting
    [switch]$Yes                # skip the YES question (testing only)
)
$ErrorActionPreference = 'Stop'
$OldTask = 'MC Server Manager'; $NewTask = 'MC.SESH'
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$oldTaskRemoved = $false

function Say($m, $c = 'White') { Write-Host $m -ForegroundColor $c }
function Step($n, $m) { Write-Host ''; Write-Host "[$n] $m" -ForegroundColor Cyan }

try {
    Say ''
    Say 'MC.SESH migration' 'Green'
    Say '-----------------'

    # ---- checks
    $isAdmin = ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
    if (-not $isAdmin -and -not $NoSystemChanges) { throw 'This needs Administrator rights. Run Migrate-To-MCSESH.bat (it asks Windows for approval).' }
    foreach ($p in @("$OldDir\data\config.json", "$OldDir\tools\Stop-Manager.ps1")) { if (-not (Test-Path -LiteralPath $p)) { throw "The old installation was not found ($p is missing). Check -OldDir." } }
    foreach ($p in @("$NewDir\app\mcmanager.jar", "$NewDir\runtime\bin\java.exe", "$NewDir\tools\Set-Startup.ps1", "$NewDir\Start.bat")) { if (-not (Test-Path -LiteralPath $p)) { throw "The new MC.SESH is not installed at $NewDir ($p is missing). Run the MC.SESH installer first." } }
    if ((Resolve-Path $OldDir).Path.TrimEnd('\') -eq (Resolve-Path $NewDir).Path.TrimEnd('\')) { throw 'The old and new folders are the same.' }

    $oldCfg = Get-Content -LiteralPath "$OldDir\data\config.json" -Raw | ConvertFrom-Json
    $port = if ($oldCfg.port) { [int]$oldCfg.port } else { 8585 }
    $servers = @($oldCfg.servers)
    $inside = @($servers | Where-Object { $_.dir -and ([IO.Path]::GetFullPath([string]$_.dir)).StartsWith(([IO.Path]::GetFullPath($OldDir)).TrimEnd('\') + '\', [StringComparison]::OrdinalIgnoreCase) })
    $backupsOld = Join-Path $OldDir 'backups'
    $backupsGb = if (Test-Path -LiteralPath $backupsOld) { [math]::Round((Get-ChildItem -LiteralPath $backupsOld -Recurse -File -ErrorAction SilentlyContinue | Measure-Object Length -Sum).Sum / 1GB, 2) } else { 0 }
    $task = Get-ScheduledTask -TaskName $OldTask -ErrorAction SilentlyContinue
    $mode = 'off'
    if ($task) {
        $trig = @($task.Triggers | ForEach-Object { $_.CimClass.CimClassName })
        $mode = if ($trig -match 'Boot') { 'boot' } elseif ($trig -match 'Logon') { 'logon' } else { 'other' }
    }

    Say "Old installation : $OldDir"
    Say "New installation : $NewDir"
    Say "Web page port    : $port (kept)"
    Say "Servers managed  : $($servers.Count) ($(($servers | ForEach-Object { $_.name }) -join ', '))"
    Say "Old startup task : $(if ($task) { "$OldTask ($mode, runs as $($task.Principal.UserId))" } else { 'none' })"
    Say "Old backups      : $backupsGb GB in $backupsOld (will be moved to $NewDir\backups)"
    if ($inside.Count) {
        Say ''
        Say 'WARNING: these servers live INSIDE the old folder and stay there:' 'Yellow'
        $inside | ForEach-Object { Say "   $($_.name)  ($($_.dir))" 'Yellow' }
        Say 'Never delete the old folder while it contains them.' 'Yellow'
    }
    Say ''
    Say 'Your Minecraft servers keep running and nobody is disconnected. The web page is unavailable for about a minute.'
    if (-not $Yes) {
        $a = Read-Host 'Type YES to continue'
        if ($a -ne 'YES') { Say 'Cancelled. Nothing was changed.' 'Yellow'; return }
    }

    # ---- 1. stop the old manager (not the servers)
    Step 1 'Stopping the old manager (the Minecraft servers keep running)'
    if (-not $NoSystemChanges) {
        & powershell.exe -NoProfile -ExecutionPolicy Bypass -File "$OldDir\tools\Stop-Manager.ps1" -InstallDir $OldDir -Port $port | ForEach-Object { Say "   $_" }
        if ($LASTEXITCODE -ne 0) { throw 'The old manager could not be stopped.' }
        for ($i = 0; $i -lt 30; $i++) { $t = Get-ScheduledTask -TaskName $OldTask -ErrorAction SilentlyContinue; if (-not $t -or $t.State -ne 'Running') { break }; Start-Sleep -Seconds 1 }
        # the new install may have been started by the installer ("Start MC.SESH now"); it must not hold its data folder open
        & powershell.exe -NoProfile -ExecutionPolicy Bypass -File "$NewDir\tools\Stop-Manager.ps1" -InstallDir $NewDir | ForEach-Object { Say "   $_" }
        if ($LASTEXITCODE -ne 0) { throw 'The new MC.SESH (started by the installer) could not be stopped.' }
    } else { Say '   (skipped: test mode)' 'DarkGray' }

    # ---- 2. safety copy
    Step 2 'Making a safety copy of the old settings'
    $safe = Join-Path $OldDir "data-before-migration-$stamp"
    robocopy "$OldDir\data" $safe /E /XD cache /R:1 /W:1 /NFL /NDL /NJH /NJS /NP | Out-Null
    if ($LASTEXITCODE -ge 8) { throw "Could not copy the old data folder (robocopy $LASTEXITCODE)." }
    Say "   saved to $safe"

    # ---- 3. data
    Step 3 'Copying settings, accounts, notifications and caches into the new install'
    $newData = Join-Path $NewDir 'data'
    if (Test-Path -LiteralPath $newData) {
        $fresh = Join-Path $NewDir "data-fresh-install-$stamp"
        Move-Item -LiteralPath $newData -Destination $fresh
        Say "   the new install's own data was kept as $fresh"
    }
    robocopy "$OldDir\data" $newData /E /XF shutdown.token /XD "data-before-migration-*" /R:1 /W:1 /NFL /NDL /NJH /NJS /NP | Out-Null
    if ($LASTEXITCODE -ge 8) { throw "Could not copy the data (robocopy $LASTEXITCODE)." }
    [IO.File]::WriteAllText((Join-Path $newData 'port.txt'), "$port", (New-Object Text.ASCIIEncoding))
    # the settings file is copied unchanged, except that a "backupsRoot" chosen during the new install is dropped so backups stay in $NewDir\backups
    $cfgPath = Join-Path $newData 'config.json'
    $cfgText = [IO.File]::ReadAllText($cfgPath)
    if ($cfgText -match '"backupsRoot"') { throw 'The copied settings already contain a backupsRoot; stopping so nothing is guessed.' }
    Say "   done ($((Get-ChildItem -LiteralPath $newData -Recurse -File | Measure-Object).Count) files)"

    # ---- 4. backups
    Step 4 'Moving the old backups'
    $backupsNew = Join-Path $NewDir 'backups'
    if (Test-Path -LiteralPath $backupsOld) {
        if (-not (Test-Path -LiteralPath $backupsNew)) {
            Move-Item -LiteralPath $backupsOld -Destination $backupsNew
            Say "   moved $backupsGb GB to $backupsNew"
        } else {
            $moved = 0
            foreach ($c in Get-ChildItem -LiteralPath $backupsOld -Force) {
                $dest = Join-Path $backupsNew $c.Name
                if (-not (Test-Path -LiteralPath $dest)) { Move-Item -LiteralPath $c.FullName -Destination $dest; $moved++ } else { Say "   left $($c.Name) in place (the new folder already has one)" 'Yellow' }
            }
            Say "   moved $moved folder(s) into $backupsNew"
        }
    } else { Say '   there was no backups folder' }

    # ---- 5. startup task, firewall, shortcuts
    Step 5 'Replacing the startup task and the firewall rule'
    if (-not $NoSystemChanges) {
        $helper = Join-Path $NewDir 'tools\Set-Startup.ps1'
        if ($mode -in 'boot', 'logon') {
            Say "   creating the '$NewTask' startup task ($mode); Windows asks for your password once"
            & powershell.exe -NoProfile -ExecutionPolicy Bypass -File $helper -Action $mode -InstallDir $NewDir -Port $port -TaskName $NewTask -FwRuleName $NewTask -NoPause
            if ($LASTEXITCODE -ne 0) { throw 'Creating the new startup task failed.' }
        } else {
            & powershell.exe -NoProfile -ExecutionPolicy Bypass -File $helper -Action firewall -InstallDir $NewDir -Port $port -FwRuleName $NewTask -NoPause
            if ($LASTEXITCODE -ne 0) { throw 'Creating the new firewall rule failed.' }
        }
        # only now that the new task exists is the old one removed (never while it is running: that would end the servers it started)
        $t = Get-ScheduledTask -TaskName $OldTask -ErrorAction SilentlyContinue
        if ($t) {
            if ($t.State -eq 'Running') { Disable-ScheduledTask -TaskName $OldTask | Out-Null; Say "   the old task was still running, so it was only disabled (remove it later in Task Scheduler)" 'Yellow' }
            else { Unregister-ScheduledTask -TaskName $OldTask -Confirm:$false; Say "   old startup task '$OldTask' removed" }
            $oldTaskRemoved = $true
        }
        Get-NetFirewallRule -DisplayName $OldTask -ErrorAction SilentlyContinue | Remove-NetFirewallRule
        Say "   old firewall rule '$OldTask' removed"
        foreach ($d in @([Environment]::GetFolderPath('Desktop'), [Environment]::GetFolderPath('CommonDesktopDirectory'), [Environment]::GetFolderPath('Programs'), [Environment]::GetFolderPath('CommonPrograms'))) {
            Remove-Item -LiteralPath (Join-Path $d 'MC Server Manager.lnk') -Force -ErrorAction SilentlyContinue
        }
        Say '   old shortcuts removed'
        # new shortcuts (the installer normally makes these)
        $icon = Join-Path $NewDir 'mcsesh.ico'
        $sh = New-Object -ComObject WScript.Shell
        foreach ($d in @([Environment]::GetFolderPath('Desktop'), [Environment]::GetFolderPath('Programs'))) {
            $lnk = $sh.CreateShortcut((Join-Path $d 'MC.SESH.lnk'))
            $lnk.TargetPath = Join-Path $NewDir 'Start.bat'
            $lnk.WorkingDirectory = $NewDir
            $lnk.WindowStyle = 7
            if (Test-Path -LiteralPath $icon) { $lnk.IconLocation = $icon }
            $lnk.Description = 'Start MC.SESH and open it in your browser'
            $lnk.Save()
        }
        Say '   MC.SESH shortcuts created (Desktop and Start Menu)'
    } else { Say '   (skipped: test mode)' 'DarkGray' }

    # ---- 6. start
    Step 6 'Starting MC.SESH'
    if (-not $NoSystemChanges) {
        if (Get-ScheduledTask -TaskName $NewTask -ErrorAction SilentlyContinue) { Start-ScheduledTask -TaskName $NewTask }
        else { Start-Process -FilePath (Join-Path $NewDir 'Start.bat') -ArgumentList 'quiet' -WorkingDirectory $NewDir -WindowStyle Hidden }
        $up = $false
        for ($i = 0; $i -lt 60; $i++) { Start-Sleep -Seconds 1; try { $r = Invoke-WebRequest "http://localhost:$port/" -UseBasicParsing -TimeoutSec 3; if ($r.StatusCode -eq 200) { $up = $true; break } } catch { } }
        if (-not $up) { throw "MC.SESH did not answer on port $port within a minute. Look at $newData\manager.log." }
        Say "   MC.SESH is up on port $port" 'Green'
    } else { Say '   (skipped: test mode)' 'DarkGray' }

    Say ''
    Say 'Done.' 'Green'
    Say "Open MC.SESH in your browser (http://localhost:$port) and sign in as before; your servers, accounts and backups came across."
    Say "Your servers kept running during the move. Servers started by the new manager use the new install from now on."
    if ($inside.Count) { Say "Do NOT delete ${OldDir}: it still holds $(($inside | ForEach-Object { $_.name }) -join ', ')." 'Yellow' }
    else { Say "When you are happy with everything, $OldDir can be deleted." }
}
catch {
    Say ''
    Say "Something went wrong: $($_.Exception.Message)" 'Red'
    if (-not $NoSystemChanges -and -not $oldTaskRemoved) {
        Say 'Starting the old manager again so nothing is left stopped...' 'Yellow'
        try {
            if (Get-ScheduledTask -TaskName $OldTask -ErrorAction SilentlyContinue) { Start-ScheduledTask -TaskName $OldTask }
            else { Start-Process -FilePath (Join-Path $OldDir 'Start.bat') -ArgumentList 'quiet' -WorkingDirectory $OldDir -WindowStyle Hidden }
            Say 'The old manager was started again. Your servers were not touched.' 'Yellow'
        } catch { Say "Could not start the old manager: $($_.Exception.Message). Start it with $OldDir\Start.bat." 'Red' }
    }
    exit 1
}
