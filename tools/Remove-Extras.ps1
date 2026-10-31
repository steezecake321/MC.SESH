<#
Removes what MC.SESH added outside its own folder: its startup task and firewall rule (called "MC.SESH", or "MC Server Manager" on a PC set up
before the rename). Only things that belong to THIS install folder are removed. Needs Administrator rights; run by the uninstaller.
#>
param([Parameter(Mandatory)][string]$InstallDir)
$ErrorActionPreference = 'SilentlyContinue'
$dir = [IO.Path]::GetFullPath($InstallDir).TrimEnd('\')
foreach ($name in 'MC.SESH', 'MC Server Manager') {
    $t = Get-ScheduledTask -TaskName $name
    $mine = $false
    if ($t) { foreach ($a in $t.Actions) { if ((([string]$a.Execute + ' ' + [string]$a.Arguments + ' ' + [string]$a.WorkingDirectory) -replace '"', '') -like "*$dir*") { $mine = $true } } }
    if ($mine) {
        Stop-ScheduledTask -TaskName $name
        Unregister-ScheduledTask -TaskName $name -Confirm:$false
        Get-NetFirewallRule -DisplayName $name | Remove-NetFirewallRule
        "Removed the '$name' startup task and firewall rule."
    } elseif (-not $t -and $name -eq 'MC.SESH') {
        Get-NetFirewallRule -DisplayName $name | Remove-NetFirewallRule
    }
}
