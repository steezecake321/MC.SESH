<#
Set-Startup.ps1 - changes how MC.SESH starts with Windows. Needs Administrator rights.
Used by the app's Settings > Startup page and by Setup.ps1 (the app starts it elevated; you approve the Windows prompt).

  -Action off         remove the startup task
  -Action logon       start the manager when the user signs in
  -Action boot        start the manager when the PC starts, BEFORE anyone signs in (asks for the Windows password once;
                      it goes straight to Task Scheduler and is never seen by the app)
  -Action firewall    allow the manager's port through Windows Firewall on private networks
  -Action bedrockFirewall  allow a Bedrock/Geyser UDP port (-Port) through Windows Firewall on private networks
  -Action gameFirewall  allow the Minecraft servers' TCP game ports (-Ports '6768,6769') through Windows Firewall on private networks, whatever Java runs them
  -Action removeTask  delete other scheduled tasks by name (-Tasks 'name1|name2'), e.g. old per-server start tasks
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory)][ValidateSet('off', 'logon', 'boot', 'firewall', 'bedrockFirewall', 'gameFirewall', 'removeTask')][string]$Action,
    [string]$InstallDir,
    [string]$Ports = '',
    [int]$Port = 8585,
    [int]$Port2 = 0,
    [string]$User = "$env:USERDOMAIN\$env:USERNAME",
    [string]$TaskName = '',
    [string]$FwRuleName = '',
    [string]$Tasks = '',
    [string]$ResultFile,
    [string]$LogFile,
    [switch]$AsSystem,
    [switch]$NoPause
)
$ErrorActionPreference = 'Stop'
# The task and the firewall rule are called MC.SESH. A PC set up before the rename keeps its "MC Server Manager" ones until it is migrated.
$NewName = 'MC.SESH'; $OldName = 'MC Server Manager'
if (-not $TaskName) { $TaskName = if (Get-ScheduledTask -TaskName $NewName -ErrorAction SilentlyContinue) { $NewName } elseif (Get-ScheduledTask -TaskName $OldName -ErrorAction SilentlyContinue) { $OldName } else { $NewName } }
if (-not $FwRuleName) { $FwRuleName = if (Get-NetFirewallRule -DisplayName $NewName -ErrorAction SilentlyContinue) { $NewName } elseif (Get-NetFirewallRule -DisplayName $OldName -ErrorAction SilentlyContinue) { $OldName } else { $NewName } }

function Write-Result($ok, $message, $detail = '') {
    $logPath = if ($LogFile) { $LogFile } elseif ($InstallDir) { Join-Path $InstallDir 'data\startup-last.log' } else { $null }
    if ($logPath) {
        try {
            New-Item -ItemType Directory -Force (Split-Path $logPath) | Out-Null
            $state = if ($ok) { 'OK' } else { 'ERROR' }
            Add-Content -LiteralPath $logPath -Value ("{0} [{1}] {2}: {3} {4}" -f (Get-Date -Format 'yyyy-MM-dd HH:mm:ss'), $state, $Action, $message, $detail)
        } catch { }
    }
    if ($ResultFile) {
        $json = @{ ok = $ok; message = $message; action = $Action } | ConvertTo-Json -Compress
        [IO.File]::WriteAllText($ResultFile, $json, (New-Object Text.UTF8Encoding($false)))
    }
    if ($ok) {
        Write-Host $message -ForegroundColor Green
        if (-not $NoPause) { Start-Sleep -Seconds 3 }
    } else {
        Write-Host ''
        Write-Host "ERROR: $message" -ForegroundColor Red
        if ($detail) { Write-Host $detail }
        if ($logPath) { Write-Host "Saved to: $logPath" }
        if (-not $NoPause) {
            Write-Host ''
            Write-Host 'Press any key to close this window...'
            try { [void][Console]::ReadKey($true) } catch { Start-Sleep -Seconds 60 }
        }
    }
}

try {
    $id = [Security.Principal.WindowsIdentity]::GetCurrent()
    if (-not ([Security.Principal.WindowsPrincipal]$id).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
        throw 'This needs Administrator rights. Approve the Windows prompt, or run it from an Administrator PowerShell.'
    }

    # Never stop or delete the running task: the running manager (and servers it started) belong to it and would be killed.
    function Disable-OwnTask {
        if (Get-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue) { Disable-ScheduledTask -TaskName $TaskName | Out-Null }
    }

    function Ensure-Firewall {
        Get-NetFirewallRule -DisplayName $FwRuleName -ErrorAction SilentlyContinue | Remove-NetFirewallRule
        New-NetFirewallRule -DisplayName $FwRuleName -Direction Inbound -Protocol TCP -LocalPort $Port -Action Allow -Profile Private | Out-Null
    }

    function New-ManagerAction {
        if (-not $InstallDir -or -not (Test-Path (Join-Path $InstallDir 'Start.bat'))) { throw "Start.bat not found in '$InstallDir'. Startup can only be set up for the installed app." }
        return New-ScheduledTaskAction -Execute 'cmd.exe' -Argument "/c `"$InstallDir\Start.bat`" service" -WorkingDirectory $InstallDir
    }

    function New-ManagerSettings {
        return New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries -StartWhenAvailable -ExecutionTimeLimit ([TimeSpan]::Zero) `
            -MultipleInstances IgnoreNew -RestartCount 5 -RestartInterval (New-TimeSpan -Minutes 1)
    }

    switch ($Action) {
        'off' {
            Disable-OwnTask
            Write-Result $true 'The manager will no longer start automatically (the startup task is disabled).'
        }
        'logon' {
            $taskAct = New-ManagerAction
            $trigger = New-ScheduledTaskTrigger -AtLogOn -User $User
            $principal = New-ScheduledTaskPrincipal -UserId $User -LogonType Interactive -RunLevel Highest
            Register-ScheduledTask -TaskName $TaskName -Action $taskAct -Trigger $trigger -Principal $principal -Settings (New-ManagerSettings) -Force `
                -Description 'Starts MC.SESH when you sign in' | Out-Null
            Ensure-Firewall
            Write-Result $true "The manager will start when $User signs in."
        }
        'boot' {
            $taskAct = New-ManagerAction
            $useSystem = [bool]$AsSystem
            $cred = $null
            if (-not $useSystem) {
                $cred = Get-Credential -UserName $User -Message 'Windows password for the "MC.SESH" startup task (goes straight to Windows Task Scheduler)'
                if (-not $cred) { throw 'Cancelled.' }
                if ([string]::IsNullOrEmpty($cred.GetNetworkCredential().Password)) {
                    Write-Host 'No password was entered. Windows cannot run a "before sign-in" task for an account that has no password.' -ForegroundColor Yellow
                    $ans = Read-Host 'Run it as the Windows system account instead (no password needed; servers then run as SYSTEM)? [y/N]'
                    if ($ans -match '^(y|yes)$') { $useSystem = $true } else { throw 'Cancelled: no password was entered.' }
                }
            }
            $trigger = New-ScheduledTaskTrigger -AtStartup
            $trigger.Delay = 'PT30S'
            # Second trigger as a safety net: if the start-up one was missed for any reason, signing in starts the manager (a second copy just exits).
            $triggers = @($trigger, (New-ScheduledTaskTrigger -AtLogOn))
            if ($useSystem) {
                $principal = New-ScheduledTaskPrincipal -UserId 'SYSTEM' -LogonType ServiceAccount -RunLevel Highest
                Register-ScheduledTask -TaskName $TaskName -Action $taskAct -Trigger $triggers -Principal $principal -Settings (New-ManagerSettings) -Force `
                    -Description 'Starts MC.SESH when the PC starts, before sign-in (system account)' | Out-Null
                Ensure-Firewall
                Write-Result $true 'The manager will start 30 seconds after the PC starts, before sign-in, running as the Windows system account.'
            } else {
                Register-ScheduledTask -TaskName $TaskName -Action $taskAct -Trigger $triggers -Settings (New-ManagerSettings) -RunLevel Highest -Force `
                    -User $cred.UserName -Password $cred.GetNetworkCredential().Password -Description 'Starts MC.SESH when the PC starts, before sign-in' | Out-Null
                Ensure-Firewall
                Write-Result $true 'The manager will start 30 seconds after the PC starts, even before anyone signs in.'
            }
        }
        'firewall' {
            Ensure-Firewall
            Write-Result $true "Windows Firewall now allows other devices on your private network to reach the manager (port $Port)."
        }
        'bedrockFirewall' {
            if ($Port -lt 1024 -or $Port -gt 65535) { throw "Bad port: $Port" }
            $ruleName = "MC Bedrock $Port"
            Get-NetFirewallRule -DisplayName $ruleName -ErrorAction SilentlyContinue | Remove-NetFirewallRule
            New-NetFirewallRule -DisplayName $ruleName -Direction Inbound -Protocol UDP -LocalPort $Port -Action Allow -Profile Private | Out-Null
            # NetherNet: players find the server over TCP on the same port, then play over UDP on the second port.
            Get-NetFirewallRule -DisplayName "$ruleName TCP" -ErrorAction SilentlyContinue | Remove-NetFirewallRule
            New-NetFirewallRule -DisplayName "$ruleName TCP" -Direction Inbound -Protocol TCP -LocalPort $Port -Action Allow -Profile Private | Out-Null
            $msg = "Windows Firewall now allows Bedrock players on your private network to reach TCP and UDP port $Port"
            if ($Port2 -ge 1024 -and $Port2 -le 65535) {
                $r2 = "MC Bedrock $Port2"
                Get-NetFirewallRule -DisplayName $r2 -ErrorAction SilentlyContinue | Remove-NetFirewallRule
                New-NetFirewallRule -DisplayName $r2 -Direction Inbound -Protocol UDP -LocalPort $Port2 -Action Allow -Profile Private | Out-Null
                $msg += " and UDP port $Port2"
            }
            Write-Result $true ($msg + '.')
        }
        'gameFirewall' {
            $list = @($Ports -split '[,\s]+' | Where-Object { $_ } | ForEach-Object { [int]$_ } | Where-Object { $_ -ge 1024 -and $_ -le 65535 } | Select-Object -Unique)
            if (-not $list.Count) { throw 'No valid ports given.' }
            foreach ($p in $list) {
                $ruleName = "MC.SESH server $p"
                Get-NetFirewallRule -DisplayName $ruleName -ErrorAction SilentlyContinue | Remove-NetFirewallRule
                New-NetFirewallRule -DisplayName $ruleName -Direction Inbound -Protocol TCP -LocalPort $p -Action Allow -Profile Private | Out-Null
            }
            Write-Result $true ("Windows Firewall now allows players on your private network to reach port" + $(if ($list.Count -gt 1) { 's' } else { '' }) + ' ' + ($list -join ', ') + '.')
        }
        'removeTask' {
            $names =@($Tasks -split '\|' | Where-Object { $_.Trim() })
            if (-not $names.Count) { throw 'No task names given.' }
            foreach ($n in $names) {
                if ($n -eq $TaskName) { throw 'Refusing to remove the manager''s own startup task here.' }
                $t = Get-ScheduledTask -TaskName $n -ErrorAction SilentlyContinue
                if ($t) {
                    if ($t.State -eq 'Running') { throw "Task '$n' is still running. Stop its server and wait until the task shows Ready, then try again." }
                    Unregister-ScheduledTask -TaskName $n -Confirm:$false
                }
            }
            Write-Result $true ('Removed: ' + ($names -join ', '))
        }
    }
} catch {
    Write-Result $false $_.Exception.Message (($_ | Out-String).Trim())
    exit 1
}
