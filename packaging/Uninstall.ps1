<#
Removes what Setup.ps1 added outside the app folder (shortcuts, firewall rule, startup task) and stops MC.SESH.
It does NOT delete your Minecraft servers or this folder; delete the folder yourself if you want it gone.
#>
$ErrorActionPreference = 'SilentlyContinue'
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
& "$here\Stop.bat" | Out-Null
foreach ($d in @([Environment]::GetFolderPath('Desktop'), [Environment]::GetFolderPath('Programs'))) { foreach ($n in 'MC.SESH.lnk', 'MC Server Manager.lnk') { Remove-Item -LiteralPath (Join-Path $d $n) -Force } }
$isAdmin = ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
if ($isAdmin) {
    & "$here\tools\Remove-Extras.ps1" -InstallDir $here
    'Removed shortcuts, firewall rule and startup task.'
} else {
    'Removed shortcuts. Run as Administrator to also remove the firewall rule and startup task.'
}
