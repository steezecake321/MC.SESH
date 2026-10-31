<#
Builds the MC.SESH installer:  dist\MC.SESH-Setup-<version>.exe
Runs build-dist.ps1 first (compiles, bundles the Java runtime), then Inno Setup. Needs Inno Setup 6 (winget install JRSoftware.InnoSetup).
Usage:  powershell -ExecutionPolicy Bypass -File build-installer.ps1 -Version 2.14.0 [-SkipDist]
#>
param([Parameter(Mandatory)][string]$Version, [switch]$SkipDist)
$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
$iscc = @("$env:LOCALAPPDATA\Programs\Inno Setup 6\ISCC.exe", "${env:ProgramFiles(x86)}\Inno Setup 6\ISCC.exe", "$env:ProgramFiles\Inno Setup 6\ISCC.exe") | Where-Object { Test-Path $_ } | Select-Object -First 1
if (-not $iscc) { throw 'Inno Setup 6 not found. Install it with:  winget install JRSoftware.InnoSetup' }
if (-not $SkipDist) { & (Join-Path $root 'build-dist.ps1') -Version $Version; if ($LASTEXITCODE -ne 0) { throw 'build-dist failed' } }
& $iscc "/DAppVersion=$Version" (Join-Path $root 'installer\mcsesh.iss')
if ($LASTEXITCODE -ne 0) { throw 'Inno Setup failed' }
Get-Item (Join-Path $root "dist\MC.SESH-Setup-$Version.exe") | ForEach-Object { "Built $($_.FullName) ({0:N1} MB)" -f ($_.Length / 1MB) }
