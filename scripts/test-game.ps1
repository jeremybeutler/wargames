<#
.SYNOPSIS
  Starts a throwaway test game and opens one browser tab per seat.

.DESCRIPTION
  Opens a host tab, which creates the game, plus one tab per character, which each join
  their seat as soon as the host has a game code. All tabs are in your default browser
  and share its cookies, so every tab acts as its own seat.

  If the API (port 8080) or the Vite dev server (port 5173) isn't running, each is started in
  its own window first. Code changes in the frontend reload live through Vite; backend
  changes need that backend window restarted.

  Quick test games only run on localhost, and they are left off the home screen's
  saved-games list.

.EXAMPLE
  .\scripts\test-game.ps1
  .\scripts\test-game.ps1 -Players 8 -Start
  .\scripts\test-game.ps1 -Leaders @{ frodo = 'Sam'; aragorn = 'Gandalf' }
  .\scripts\test-game.ps1 -Url http://localhost:8080   # against play.ps1's built app
#>
param(
    [ValidateSet(6, 8)][int]$Players = 6,
    # Start the campaign as soon as every seat has joined (otherwise you stay in the lobby).
    [switch]$Start,
    # Leader picks for parties that have them, e.g. @{ frodo = 'Sam'; theoden = 'Éowyn' }.
    [hashtable]$Leaders = @{},
    [string]$Url = 'http://localhost:5173',
    # Don't start any servers; just open the tabs.
    [switch]$NoServers
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot

function Test-Url($address) {
    try { Invoke-WebRequest -UseBasicParsing -Uri $address -TimeoutSec 3 | Out-Null; return $true } catch { return $false }
}

function Wait-Url($address, $label, $seconds = 180) {
    Write-Host "Waiting for $label..." -NoNewline
    for ($i = 0; $i -lt $seconds; $i++) {
        if (Test-Url $address) { Write-Host ' ready.'; return }
        Start-Sleep 1
        if ($i % 5 -eq 4) { Write-Host '.' -NoNewline }
    }
    Write-Host ''
    throw "$label did not start within $seconds seconds. Check its window for errors."
}

$base = $Url.TrimEnd('/')
if (-not $NoServers) {
    if (-not (Test-Url 'http://localhost:8080/api/campaigns')) {
        Write-Host 'Starting the API on port 8080 in a new window...'
        Start-Process powershell -WorkingDirectory (Join-Path $root 'backend') -ArgumentList '-NoExit', '-Command', 'mvn spring-boot:run'
        Wait-Url 'http://localhost:8080/api/campaigns' 'the API'
    }
    if ($base -eq 'http://localhost:5173' -and -not (Test-Url $base)) {
        Write-Host 'Starting the Vite dev server on port 5173 in a new window...'
        Start-Process powershell -WorkingDirectory (Join-Path $root 'frontend') -ArgumentList '-NoExit', '-Command', 'npm run dev'
        Wait-Url $base 'the dev server'
    }
}
if (-not (Test-Url $base)) { throw "Nothing is answering at $base." }

$seats = @('frodo', 'theoden', 'aragorn', 'khamul', 'saruman', 'sauron')
if ($Players -eq 8) { $seats += @('elves', 'witchking') }
$group = -join ((1..10) | ForEach-Object { '{0:x}' -f (Get-Random -Maximum 16) })

$hostUrl = "$base/?quicktest=host&group=$group&players=$Players" + $(if ($Start) { '&start=1' } else { '' })
Write-Host "Opening the host tab and $($seats.Count) player tabs..."
Start-Process $hostUrl
Start-Sleep -Milliseconds 800
foreach ($seat in $seats) {
    $joinUrl = "$base/?quicktest=join&group=$group&seat=$seat"
    if ($Leaders.ContainsKey($seat)) { $joinUrl += '&leader=' + [uri]::EscapeDataString($Leaders[$seat]) }
    Start-Process $joinUrl
    Start-Sleep -Milliseconds 250
}
Write-Host 'Done. The host tab shows the game code; each player tab joins its seat on its own.'
