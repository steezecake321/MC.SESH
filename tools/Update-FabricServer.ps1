<#
Update-FabricServer.ps1
Keeps a Fabric server current: Minecraft server, Fabric loader, and every mod in mods\ (Geyser, Floodgate, Fabric API, ...).
Called from each server's start.bat before Java launches. It never blocks startup: any failure is logged and the script exits 0.

Rules:
  * Mods are identified by file hash on Modrinth. Jars Modrinth does not know are left alone (and block a Minecraft upgrade).
  * Minecraft only moves to a newer stable release when EVERY installed mod has a build for it (e.g. Geyser lags new releases).
    The world is zipped to updater-backups\ before any Minecraft version change.
  * Replaced files are moved to updater-backups\<timestamp>\ so an update can be rolled back by hand.
  * If the server is already listening on its port, nothing is touched.

Switches: -DryRun (report only, change nothing), -NoMinecraftUpgrade (still updates loader and mods within the current Minecraft version).
#>
[CmdletBinding()]
param(
    [string]$ServerDir = (Get-Location).Path,
    [switch]$DryRun,
    [switch]$NoMinecraftUpgrade,
    [string]$CacheDir = (Join-Path $env:TEMP 'mc-updater-cache')
)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
Add-Type -AssemblyName System.IO.Compression.FileSystem

$ServerDir  = (Resolve-Path -LiteralPath $ServerDir).Path.TrimEnd('\')
if (Test-Path -LiteralPath (Join-Path $ServerDir 'pin-minecraft.txt')) { $NoMinecraftUpgrade = [switch]$true }
$JavaExe    = if ($env:MCM_JAVA) { $env:MCM_JAVA } else { 'java' }
$BackupRoot = Join-Path $ServerDir 'updater-backups'
$LogFile    = Join-Path $ServerDir 'updater.log'
$Stamp      = Get-Date -Format 'yyyyMMdd-HHmmss'
$RunBackup  = Join-Path $BackupRoot $Stamp
$Headers    = @{ 'User-Agent' = 'MCServerUpdater/1.0' }
$Modrinth   = 'https://api.modrinth.com/v2'
$FabricMeta = 'https://meta.fabricmc.net/v2'

function Write-Log {
    param([string]$Message, [string]$Level = 'INFO')
    Write-Host "[Updater] $Message"
    try { Add-Content -LiteralPath $LogFile -Value ('{0} [{1}] {2}' -f (Get-Date -Format 'yyyy-MM-dd HH:mm:ss'), $Level, $Message) } catch {}
}

function Invoke-Api {
    param([string]$Uri, $Body, [string]$Method = 'Get', [int]$Retries = 3)
    for ($i = 1; $i -le $Retries; $i++) {
        try {
            if ($Body) { $r = Invoke-RestMethod -Uri $Uri -Method $Method -Headers $Headers -TimeoutSec 20 -ContentType 'application/json' -Body $Body }
            else { $r = Invoke-RestMethod -Uri $Uri -Method $Method -Headers $Headers -TimeoutSec 20 }
            foreach ($x in $r) { $x }
            return
        } catch {
            if ($i -eq $Retries) { throw }
            Start-Sleep -Seconds (3 * $i)
        }
    }
}

function Save-Download {
    param([string]$Url, [string]$Path, [string]$Sha512)
    if (Test-Path -LiteralPath $Path) {
        if (-not $Sha512 -or (Get-FileHash -LiteralPath $Path -Algorithm SHA512).Hash -eq $Sha512) { return }
        Remove-Item -LiteralPath $Path -Force
    }
    New-Item -ItemType Directory -Force -Path (Split-Path $Path) | Out-Null
    $tmp = "$Path.part"
    Invoke-WebRequest -Uri $Url -OutFile $tmp -Headers $Headers -UseBasicParsing -TimeoutSec 600
    if ($Sha512 -and (Get-FileHash -LiteralPath $tmp -Algorithm SHA512).Hash -ne $Sha512) {
        Remove-Item -LiteralPath $tmp -Force
        throw "Checksum mismatch downloading $Url"
    }
    Move-Item -LiteralPath $tmp -Destination $Path -Force
}

function Read-ZipEntry {
    param([string]$Zip, [string]$Entry)
    $z = [IO.Compression.ZipFile]::OpenRead($Zip)
    try {
        $e = $z.GetEntry($Entry)
        if (-not $e) { return $null }
        $r = New-Object IO.StreamReader($e.Open())
        try { return $r.ReadToEnd() } finally { $r.Dispose() }
    } finally { $z.Dispose() }
}

function Read-Properties {
    param([string]$Path)
    $p = @{}
    if (Test-Path -LiteralPath $Path) {
        foreach ($l in Get-Content -LiteralPath $Path) {
            if ($l -match '^\s*([^#=\s][^=]*?)\s*=(.*)$') { $p[$Matches[1]] = $Matches[2] }
        }
    }
    $p
}

function Get-CurrentMinecraft {
    $jar = Join-Path $ServerDir 'server.jar'
    if (-not (Test-Path -LiteralPath $jar)) { return $null }
    $json = Read-ZipEntry $jar 'version.json'
    if ($json) { return ($json | ConvertFrom-Json).id }
    $null
}

function Get-CurrentLoader {
    $jar = Join-Path $ServerDir 'fabric-server-launch.jar'
    if (-not (Test-Path -LiteralPath $jar)) { return $null }
    $mf = Read-ZipEntry $jar 'META-INF/MANIFEST.MF'
    if ($mf -and (($mf -replace "\r?\n ", '') -match 'fabric-loader/([^/]+)/')) { return $Matches[1] }
    $null
}

function Get-WorldDir {
    $name = $null
    $bat = Join-Path $ServerDir 'start.bat'
    if (Test-Path -LiteralPath $bat) {
        $m = [regex]::Match((Get-Content -LiteralPath $bat -Raw), '--world\s+(?:"([^"]+)"|(\S+))')
        if ($m.Success) { if ($m.Groups[1].Success) { $name = $m.Groups[1].Value } else { $name = $m.Groups[2].Value } }
    }
    if (-not $name) {
        $props = Read-Properties (Join-Path $ServerDir 'server.properties')
        if ($props['level-name']) { $name = $props['level-name'] -replace '\\(.)', '$1' } else { $name = 'world' }
    }
    $path = Join-Path $ServerDir $name
    if (Test-Path -LiteralPath $path) { return $path }
    $null
}

function Get-InstalledMods {
    $jars = @(Get-ChildItem -LiteralPath (Join-Path $ServerDir 'mods') -Filter '*.jar' -File -ErrorAction SilentlyContinue)
    if ($jars.Count -eq 0) { return @() }
    $byHash = @{}
    foreach ($j in $jars) { $byHash[(Get-FileHash -LiteralPath $j.FullName -Algorithm SHA1).Hash.ToLower()] = $j }
    $body = @{ hashes = @($byHash.Keys); algorithm = 'sha1' } | ConvertTo-Json
    $resp = Invoke-Api "$Modrinth/version_files" -Method Post -Body $body
    $known = @{}
    foreach ($h in $byHash.Keys) {
        $v = $resp.PSObject.Properties[$h]
        if ($v) { $known[$h] = $v.Value }
    }
    $titles = @{}
    $ids = @($known.Values | ForEach-Object { $_.project_id } | Select-Object -Unique)
    if ($ids.Count -gt 0) {
        $q = [uri]::EscapeDataString((ConvertTo-Json -InputObject $ids -Compress))
        foreach ($p in @(Invoke-Api "$Modrinth/projects?ids=$q")) { $titles[$p.id] = $p.title }
    }
    foreach ($h in $byHash.Keys) {
        $file = $byHash[$h]
        if ($known.ContainsKey($h)) {
            $ver = $known[$h]
            $title = $titles[$ver.project_id]
            if (-not $title) { $title = $file.Name }
            [pscustomobject]@{ File = $file; Version = $ver; ProjectId = $ver.project_id; Title = $title }
        } else {
            [pscustomobject]@{ File = $file; Version = $null; ProjectId = $null; Title = $file.Name }
        }
    }
}

function Find-ModBuild {
    param([string]$ProjectId, [string]$Mc)
    $l = [uri]::EscapeDataString('["fabric"]')
    $g = [uri]::EscapeDataString("[""$Mc""]")
    $vs = @(Invoke-Api "$Modrinth/project/$ProjectId/version?loaders=$l&game_versions=$g")
    $vs | Where-Object { $_.version_type -ne 'alpha' } | Sort-Object { [datetime]$_.date_published } -Descending | Select-Object -First 1
}

function Get-ModPlan {
    param([object[]]$Mods, [string]$Mc, [string]$CurrentMc)
    $changingMc = ($Mc -ne $CurrentMc)
    foreach ($m in $Mods | Where-Object { $_.Version }) {
        $t = Find-ModBuild $m.ProjectId $Mc
        $ok = [bool]$t -or (-not $changingMc)
        $update = $false
        if ($t -and $t.id -ne $m.Version.id) {
            $update = $changingMc -or ([datetime]$t.date_published -gt [datetime]$m.Version.date_published)
        }
        [pscustomobject]@{ Mod = $m; Target = $t; Compatible = $ok; Update = $update }
    }
}

function Backup-Path {
    param([string]$Source, [string]$SubDir)
    $dest = Join-Path $RunBackup $SubDir
    New-Item -ItemType Directory -Force -Path $dest | Out-Null
    Copy-Item -LiteralPath $Source -Destination $dest -Force
}

function Backup-World {
    $world = Get-WorldDir
    if (-not $world) { Write-Log 'No world folder found; nothing to back up.'; return }
    New-Item -ItemType Directory -Force -Path $BackupRoot | Out-Null
    $zip = Join-Path $BackupRoot ("world-{0}-{1}.zip" -f (Split-Path $world -Leaf), $Stamp)
    Write-Log "Backing up world '$world' to $zip (this can take a minute)..."
    [IO.Compression.ZipFile]::CreateFromDirectory($world, $zip, [IO.Compression.CompressionLevel]::Fastest, $false)
    Write-Log 'World backup complete.'
}

function Install-Fabric {
    param([string]$Mc, [string]$Loader, [string]$InstallerVersion)
    $installer = Join-Path $CacheDir "fabric-installer-$InstallerVersion.jar"
    Save-Download "https://maven.fabricmc.net/net/fabricmc/fabric-installer/$InstallerVersion/fabric-installer-$InstallerVersion.jar" $installer $null
    $tracked = 'fabric-server-launch.jar', 'server.jar', 'fabric-server-launcher.properties'
    foreach ($f in $tracked) {
        $p = Join-Path $ServerDir $f
        if (Test-Path -LiteralPath $p) { Backup-Path $p 'fabric' }
    }
    $out = Join-Path $env:TEMP "fabric-installer-$Stamp.log"
    $argLine = '-jar "{0}" server -dir "{1}" -mcversion {2} -loader {3} -downloadMinecraft' -f $installer, $ServerDir, $Mc, $Loader
    $proc = Start-Process -FilePath $JavaExe -ArgumentList $argLine -Wait -PassThru -NoNewWindow -RedirectStandardOutput $out -RedirectStandardError "$out.err"
    $gotMc = Get-CurrentMinecraft
    $gotLoader = Get-CurrentLoader
    if ($proc.ExitCode -ne 0 -or $gotMc -ne $Mc -or $gotLoader -ne $Loader) {
        Write-Log ("Fabric install failed (exit {0}, server.jar={1}, loader={2}). Restoring previous files." -f $proc.ExitCode, $gotMc, $gotLoader) 'ERROR'
        foreach ($l in @(Get-Content -LiteralPath $out, "$out.err" -Tail 10 -ErrorAction SilentlyContinue)) { Write-Log "  installer: $l" 'ERROR' }
        foreach ($f in $tracked) {
            $b = Join-Path $RunBackup "fabric\$f"
            if (Test-Path -LiteralPath $b) { Copy-Item -LiteralPath $b -Destination (Join-Path $ServerDir $f) -Force }
        }
        throw 'Fabric install failed'
    }
    Remove-Item -LiteralPath $out, "$out.err" -Force -ErrorAction SilentlyContinue
}

function Remove-OldBackups {
    if (-not (Test-Path -LiteralPath $BackupRoot)) { return }
    Get-ChildItem -LiteralPath $BackupRoot -Directory | Sort-Object Name -Descending | Select-Object -Skip 5 | Remove-Item -Recurse -Force -ErrorAction SilentlyContinue
    Get-ChildItem -LiteralPath $BackupRoot -Filter 'world-*.zip' -File | Sort-Object LastWriteTime -Descending | Select-Object -Skip 2 | Remove-Item -Force -ErrorAction SilentlyContinue
}

function Invoke-Attempt {
    param([string]$Mc, [string]$CurrentMc, [string]$CurrentLoader, [object[]]$Mods, [object[]]$Plan, [string]$Loader, [string]$InstallerVersion)
    $changingMc = ($Mc -ne $CurrentMc)
    $needFabric = $changingMc -or ($Loader -ne $CurrentLoader)
    $updates = @($Plan | Where-Object { $_.Update })

    if ($changingMc) { Write-Log "Minecraft $CurrentMc -> $Mc" 'CHANGE' }
    if ($needFabric) { Write-Log "Fabric loader $CurrentLoader -> $Loader" 'CHANGE' }
    foreach ($u in $updates) { Write-Log ("{0} {1} -> {2}" -f $u.Mod.Title, $u.Mod.Version.version_number, $u.Target.version_number) 'CHANGE' }
    if (-not $changingMc -and -not $needFabric -and $updates.Count -eq 0) { Write-Log "Everything is up to date (Minecraft $CurrentMc, loader $CurrentLoader)."; return $true }
    if ($DryRun) { Write-Log 'Dry run: no changes made.'; return $true }

    foreach ($u in $updates) {
        $f = @($u.Target.files | Where-Object { $_.primary })[0]
        if (-not $f) { $f = @($u.Target.files)[0] }
        $u | Add-Member -NotePropertyName NewName -NotePropertyValue $f.filename -Force
        $u | Add-Member -NotePropertyName Cached -NotePropertyValue (Join-Path $CacheDir "$($u.Target.id)\$($f.filename)") -Force
        Save-Download $f.url $u.Cached $f.hashes.sha512
    }

    if ($changingMc) { Backup-World }
    if ($needFabric) { Install-Fabric $Mc $Loader $InstallerVersion }

    foreach ($u in $updates) {
        $old = $u.Mod.File.FullName
        $new = Join-Path (Join-Path $ServerDir 'mods') $u.NewName
        try {
            Backup-Path $old 'mods'
            Remove-Item -LiteralPath $old -Force
            Copy-Item -LiteralPath $u.Cached -Destination $new -Force
        } catch {
            Write-Log "Could not update $($u.Mod.Title): $($_.Exception.Message)" 'ERROR'
            $b = Join-Path $RunBackup "mods\$($u.Mod.File.Name)"
            if ((-not (Test-Path -LiteralPath $old)) -and (Test-Path -LiteralPath $b)) { Copy-Item -LiteralPath $b -Destination $old -Force }
        }
    }
    Remove-OldBackups
    $true
}

function Invoke-Main {
    $props = Read-Properties (Join-Path $ServerDir 'server.properties')
    $port = 25565
    if ($props['server-port']) { $port = [int]$props['server-port'] }
    if (-not $DryRun -and (Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue)) {
        Write-Log "A server is already listening on port $port; skipping updates." 'WARN'
        return
    }

    $currentMc = Get-CurrentMinecraft
    if (-not $currentMc) { Write-Log 'No server.jar found; run the Fabric installer once first. Skipping updates.' 'WARN'; return }
    $currentLoader = Get-CurrentLoader
    Write-Log "Checking updates in '$ServerDir' (Minecraft $currentMc, loader $currentLoader)"

    $installer = @(Invoke-Api "$FabricMeta/versions/installer" -Retries 4) | Where-Object { $_.stable } | Select-Object -First 1
    $stableGames = @(Invoke-Api "$FabricMeta/versions/game") | Where-Object { $_.stable } | ForEach-Object { $_.version }
    $mods = @(Get-InstalledMods)
    foreach ($m in $mods | Where-Object { -not $_.Version }) { Write-Log "Not found on Modrinth, leaving alone (blocks Minecraft upgrades): $($m.File.Name)" 'WARN' }
    $unknown = @($mods | Where-Object { -not $_.Version })

    $target = $currentMc
    $targetPlan = $null
    $idx = [array]::IndexOf(@($stableGames), $currentMc)
    if ($idx -gt 0 -and -not $NoMinecraftUpgrade -and $unknown.Count -eq 0) {
        foreach ($cand in @($stableGames)[0..($idx - 1)]) {
            $plan = @(Get-ModPlan $mods $cand $currentMc)
            $blockers = @($plan | Where-Object { -not $_.Compatible } | ForEach-Object { $_.Mod.Title })
            $hasLoader = @(Invoke-Api "$FabricMeta/versions/loader/$cand") | Where-Object { $_.loader.stable } | Select-Object -First 1
            if ($blockers.Count -eq 0 -and $hasLoader) { $target = $cand; $targetPlan = $plan; break }
            if (-not $hasLoader) { $blockers += 'Fabric loader' }
            Write-Log "Minecraft $cand is available but not installed yet; waiting on: $($blockers -join ', ')."
        }
    }

    $attempts = @($target)
    if ($target -ne $currentMc) { $attempts += $currentMc }
    foreach ($mc in $attempts) {
        try {
            $plan = $targetPlan
            if ($mc -eq $currentMc) { $plan = @(Get-ModPlan $mods $currentMc $currentMc) }
            $loader = (@(Invoke-Api "$FabricMeta/versions/loader/$mc") | Where-Object { $_.loader.stable } | Select-Object -First 1).loader.version
            if (-not $loader) { throw "No stable Fabric loader for $mc" }
            if (Invoke-Attempt $mc $currentMc $currentLoader $mods $plan $loader $installer.version) { return }
        } catch {
            Write-Log "Update to Minecraft $mc failed: $($_.Exception.Message)" 'ERROR'
        }
        if ($mc -ne $currentMc) { Write-Log "Falling back to Minecraft $currentMc." 'WARN' }
    }
}

$lock = $null
try {
    New-Item -ItemType Directory -Force -Path $CacheDir | Out-Null
    $lockPath = Join-Path $CacheDir 'updater.lock'
    for ($i = 0; $i -lt 300 -and -not $lock; $i++) {
        try { $lock = [IO.File]::Open($lockPath, 'OpenOrCreate', 'ReadWrite', 'None') } catch { Start-Sleep -Seconds 2 }
    }
    if (Test-Path -LiteralPath $LogFile) {
        if ((Get-Item -LiteralPath $LogFile).Length -gt 200KB) { Set-Content -LiteralPath $LogFile -Value (Get-Content -LiteralPath $LogFile -Tail 500) }
    }
    if (-not (Get-Command $JavaExe -ErrorAction SilentlyContinue)) { Write-Log "Java not found ($JavaExe); skipping updates." 'ERROR' }
    else { Invoke-Main }
} catch {
    Write-Log "Update check failed, continuing with the existing setup: $($_.Exception.Message)" 'ERROR'
} finally {
    if ($lock) { $lock.Dispose() }
}
exit 0
