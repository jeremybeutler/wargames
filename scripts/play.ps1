<#
.SYNOPSIS
    Builds the game, starts it on this computer, and shares it with family.

.DESCRIPTION
    1. Builds the Vue frontend and bundles it into the Spring Boot jar.
    2. Starts the jar on port 8080 (saved games stay in backend/data).
    3. Opens a free tunnel and prints an https:// link to share:
       - cloudflare (default): a Cloudflare quick tunnel, no account needed,
         but the trycloudflare.com link changes on every run.
       - tailscale: Tailscale Funnel, which needs a free Tailscale account
         and gives the same https://<computer>.<tailnet>.ts.net link every run.
    Press Ctrl+C to stop; the game server is stopped too.

.PARAMETER Tunnel
    Which tunnel to share the game with: cloudflare (default) or tailscale.

.PARAMETER SkipBuild
    Reuse the last built jar instead of rebuilding.

.PARAMETER LocalOnly
    Don't open a tunnel; only players on your home Wi-Fi can join.

.EXAMPLE
    .\scripts\play.ps1
.EXAMPLE
    .\scripts\play.ps1 -Tunnel tailscale
.EXAMPLE
    .\scripts\play.ps1 -SkipBuild -LocalOnly
#>
param(
    [ValidateSet('cloudflare', 'tailscale')]
    [string]$Tunnel = 'cloudflare',
    [switch]$SkipBuild,
    [switch]$LocalOnly,
    [int]$Port = 8080
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$frontend = Join-Path $root 'frontend'
$backend = Join-Path $root 'backend'
$jar = Join-Path $backend 'target\wargames-api-0.1.0.jar'

function Test-Command($name) { [bool](Get-Command $name -ErrorAction SilentlyContinue) }

function Find-Cloudflared {
    if (Test-Command 'cloudflared') { return (Get-Command 'cloudflared').Source }
    $candidates = @(
        "$env:ProgramFiles\cloudflared\cloudflared.exe",
        "${env:ProgramFiles(x86)}\cloudflared\cloudflared.exe",
        "$env:LOCALAPPDATA\Microsoft\WinGet\Links\cloudflared.exe"
    )
    foreach ($path in $candidates) { if ($path -and (Test-Path $path)) { return $path } }
    return $null
}

function Find-Tailscale {
    if (Test-Command 'tailscale') { return (Get-Command 'tailscale').Source }
    $path = Join-Path $env:ProgramFiles 'Tailscale\tailscale.exe'
    if (Test-Path $path) { return $path }
    return $null
}

function Install-WithWinget($label, $id) {
    Write-Host "$label is not installed." -ForegroundColor Yellow
    $answer = Read-Host 'Install it now with winget? [Y/n]'
    if ($answer -and $answer -notmatch '^[Yy]') {
        throw "$label is needed to share outside your home network. Rerun with -LocalOnly to skip it."
    }
    winget install --id $id --exact --accept-package-agreements --accept-source-agreements
}

function Get-TailscaleStatus($exe) {
    $json = & $exe status --json 2>$null
    if (-not $json) { return $null }
    try { return ($json | Out-String | ConvertFrom-Json) } catch { return $null }
}

# --- Check tools -------------------------------------------------------------
if (-not (Test-Command 'java')) { throw 'Java 17+ is required (java was not found on PATH).' }
if (-not $SkipBuild) {
    if (-not (Test-Command 'npm')) { throw 'Node.js 20+ is required (npm was not found on PATH).' }
    if (-not (Test-Command 'mvn')) { throw 'Maven is required (mvn was not found on PATH).' }
}

$cloudflared = $null
$tailscale = $null
$tailscaleLink = $null
if (-not $LocalOnly -and $Tunnel -eq 'cloudflare') {
    $cloudflared = Find-Cloudflared
    if (-not $cloudflared) {
        Install-WithWinget 'cloudflared (Cloudflare Tunnel)' 'Cloudflare.cloudflared'
        $cloudflared = Find-Cloudflared
        if (-not $cloudflared) { throw 'cloudflared installed, but was not found. Open a new terminal and rerun this script.' }
    }
}
if (-not $LocalOnly -and $Tunnel -eq 'tailscale') {
    $tailscale = Find-Tailscale
    if (-not $tailscale) {
        Install-WithWinget 'Tailscale' 'Tailscale.Tailscale'
        $tailscale = Find-Tailscale
        if (-not $tailscale) { throw 'Tailscale installed, but was not found. Open a new terminal and rerun this script.' }
    }
    # Sign in before the build so you are not kept waiting later.
    $status = Get-TailscaleStatus $tailscale
    if (-not $status -or $status.BackendState -ne 'Running') {
        Write-Host 'Signing in to Tailscale. Finish the sign-in in the browser window that opens.' -ForegroundColor Cyan
        & $tailscale up
        if ($LASTEXITCODE) { throw 'Tailscale sign-in did not finish. Rerun after signing in.' }
        $status = Get-TailscaleStatus $tailscale
    }
    if ($status -and $status.Self.DNSName) {
        $tailscaleLink = 'https://' + $status.Self.DNSName.TrimEnd('.')
    }
}

# --- Stop a game server this script started earlier ------------------------
# Rerunning play.ps1 restarts the game. A server still running from an earlier run holds the
# jar (so it can't be rebuilt) and the database file, so stop it first. Only java processes
# running this project's own jar are touched.
$previous = @(Get-CimInstance Win32_Process -Filter "Name='java.exe'" -ErrorAction SilentlyContinue |
    Where-Object { $_.CommandLine -and $_.CommandLine.Contains($jar) })
foreach ($old in $previous) {
    Write-Host "==> Stopping the game server from an earlier run (process $($old.ProcessId))" -ForegroundColor Cyan
    Stop-Process -Id $old.ProcessId -Force -ErrorAction SilentlyContinue
}
if ($previous.Count) { Start-Sleep -Seconds 2 }

# --- Build -------------------------------------------------------------------
if (-not $SkipBuild) {
    Write-Host '==> Building the frontend' -ForegroundColor Cyan
    Push-Location $frontend
    try {
        if (-not (Test-Path 'node_modules')) { npm install; if ($LASTEXITCODE) { throw 'npm install failed.' } }
        npm run build; if ($LASTEXITCODE) { throw 'Frontend build failed.' }
    } finally { Pop-Location }

    Write-Host '==> Building the game server' -ForegroundColor Cyan
    Push-Location $backend
    try {
        mvn -q -DskipTests package; if ($LASTEXITCODE) { throw 'Backend build failed.' }
    } finally { Pop-Location }
}
if (-not (Test-Path $jar)) { throw "No built jar at $jar. Run without -SkipBuild first." }

# --- Start the server --------------------------------------------------------
$busy = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue
if ($busy) { throw "Port $Port is already in use. Stop the other server (e.g. mvn spring-boot:run) first." }

Write-Host "==> Starting the game server on port $Port" -ForegroundColor Cyan
$logFile = Join-Path $backend 'data\server.log'
New-Item -ItemType Directory -Force (Split-Path $logFile) | Out-Null
# Run from backend/ so the database stays at backend/data/wargames.mv.db.
# The "local" profile gives the map editor an easy default token (application-local.properties).
$server = Start-Process java -ArgumentList '-jar', "`"$jar`"", "--server.port=$Port", '--spring.profiles.active=local' `
    -WorkingDirectory $backend -NoNewWindow -PassThru `
    -RedirectStandardOutput $logFile -RedirectStandardError "$logFile.err"

try {
    $deadline = (Get-Date).AddSeconds(90)
    while ($true) {
        if ($server.HasExited) {
            if (Select-String -Path $logFile -Pattern 'file is locked|already in use' -Quiet) {
                throw 'The game database is in use by another server (e.g. mvn spring-boot:run). Stop it and rerun.'
            }
            throw "Game server stopped during startup. See $logFile"
        }
        try {
            Invoke-WebRequest "http://localhost:$Port/" -UseBasicParsing -TimeoutSec 2 | Out-Null
            break
        } catch {
            if ((Get-Date) -gt $deadline) { throw "Game server did not start within 90 seconds. See $logFile" }
            Start-Sleep -Milliseconds 750
        }
    }

    Write-Host ''
    Write-Host "Game is running: http://localhost:$Port" -ForegroundColor Green
    $lanIps = Get-NetIPAddress -AddressFamily IPv4 -ErrorAction SilentlyContinue |
        Where-Object { $_.PrefixOrigin -in 'Dhcp', 'Manual' -and $_.IPAddress -notlike '169.254*' } |
        Select-Object -ExpandProperty IPAddress
    foreach ($ip in $lanIps) { Write-Host "Same Wi-Fi:      http://${ip}:$Port" }
    $editorToken = if ($env:MAP_EDITOR_TOKEN) { '(your MAP_EDITOR_TOKEN)' } else { 'admin' }
    Write-Host "Map editor:      http://localhost:$Port/?view=map  token: $editorToken"

    if ($LocalOnly) {
        Write-Host ''
        Write-Host 'Local-only mode. If phones cannot connect, allow Java through Windows Firewall.'
        Write-Host 'Press Ctrl+C to stop.'
        Wait-Process -Id $server.Id
    } elseif ($Tunnel -eq 'tailscale') {
        Write-Host ''
        Write-Host 'Opening Tailscale Funnel.' -ForegroundColor Cyan
        if ($tailscaleLink) {
            Write-Host ''
            Write-Host "  Share this link: $tailscaleLink" -ForegroundColor Green
            Write-Host '  (It stays the same every time you run this script.)'
            Write-Host ''
        }
        Write-Host 'The first time, Tailscale may print a login.tailscale.com link to turn on'
        Write-Host 'Funnel for your account; open it, approve, and the game goes live.'
        Write-Host 'Press Ctrl+C to stop.'
        Write-Host ''
        # Runs in the foreground; the public link is removed again when it exits.
        & $tailscale funnel $Port
    } else {
        Write-Host ''
        Write-Host 'Opening a Cloudflare tunnel. Share the https://....trycloudflare.com link below.' -ForegroundColor Cyan
        Write-Host 'The link changes each time you run this script. Press Ctrl+C to stop.'
        Write-Host ''
        # cloudflared logs everything to stderr; Windows PowerShell turns each such line
        # into an error record, which 'Stop' would treat as fatal. Keep going instead.
        $ErrorActionPreference = 'Continue'
        & $cloudflared tunnel --no-autoupdate --url "http://localhost:$Port" 2>&1 | ForEach-Object {
            $line = "$_"
            if ($line -match '(https://[a-z0-9-]+\.trycloudflare\.com)') {
                Write-Host ''
                Write-Host "  Share this link: $($Matches[1])" -ForegroundColor Green
                Write-Host ''
            } elseif ($line -match '\bERR\b|failed|error') {
                Write-Host $line -ForegroundColor Yellow
            }
        }
    }
} finally {
    if ($server -and -not $server.HasExited) {
        Write-Host 'Stopping the game server...'
        Stop-Process -Id $server.Id -Force
    }
}
