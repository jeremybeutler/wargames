<#
.SYNOPSIS
    Loads terrain strokes from a JSON file into a running game (e.g. the live Render app).

.DESCRIPTION
    1. Saves the game's current strokes for that map to terrain/backups/ (so you can undo).
    2. Replaces them with the strokes in -File (or adds to them with -Append).
    Needs the app's map editor token: the MAP_EDITOR_TOKEN environment variable on Render
    (Dashboard -> your service -> Environment). You are prompted for it if -Token is omitted.

.PARAMETER Url
    The game's address, e.g. https://wargames.onrender.com or http://localhost:8080

.PARAMETER File
    Terrain JSON to import. Default: terrain/middle-earth.json (made by generate_terrain.py).
    A backup file from terrain/backups/ also works, to undo an import.

.PARAMETER Append
    Add the strokes instead of replacing the map's existing ones.

.EXAMPLE
    .\scripts\import-terrain.ps1 -Url https://wargames.onrender.com
.EXAMPLE
    .\scripts\import-terrain.ps1 -Url https://wargames.onrender.com -File terrain\backups\wargames.onrender.com-20261126-090000.json
#>
param(
    [Parameter(Mandatory = $true)][string]$Url,
    [string]$File,
    [string]$Token,
    [switch]$Append,
    [switch]$Yes
)

$ErrorActionPreference = 'Stop'
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
$root = Split-Path -Parent $PSScriptRoot
if (-not $File) { $File = Join-Path $root 'terrain\middle-earth.json' }
if (-not (Test-Path $File)) { throw "Terrain file not found: $File" }
$base = $Url.TrimEnd('/')

$data = Get-Content $File -Raw -Encoding UTF8 | ConvertFrom-Json
if (-not $data.mapKey -or -not $data.mapLayer -or $null -eq $data.regions) {
    throw "$File is not a terrain file (needs mapKey, mapLayer and regions)."
}
$counts = $data.regions | Group-Object terrainType | ForEach-Object { "$($_.Count) $($_.Name)" }
Write-Host "File:   $File"
Write-Host "Map:    $($data.mapKey) / $($data.mapLayer)"
Write-Host "Strokes: $($data.regions.Count) ($($counts -join ', '))"

if (-not $Token) {
    $secure = Read-Host 'Map editor token (MAP_EDITOR_TOKEN)' -AsSecureString
    $Token = [Runtime.InteropServices.Marshal]::PtrToStringAuto(
        [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure))
}
if (-not $Token) { throw 'A map editor token is required.' }

# --- Back up what the game has now ----------------------------------------------
Write-Host "Reading current strokes from $base (a sleeping free server can take a minute to wake)..."
$query = "mapKey=$([uri]::EscapeDataString($data.mapKey))&mapLayer=$([uri]::EscapeDataString($data.mapLayer))"
# Piping unrolls the list; Windows PowerShell 5.1 otherwise returns it as one item.
$current = @(Invoke-RestMethod "$base/api/terrain-regions?$query" -TimeoutSec 180 | ForEach-Object { $_ })
$backupDir = Join-Path $root 'terrain\backups'
New-Item -ItemType Directory -Force $backupDir | Out-Null
$hostName = ([uri]$base).Host
$backup = Join-Path $backupDir "$hostName-$(Get-Date -Format 'yyyyMMdd-HHmmss').json"
$backupData = [ordered]@{
    mapKey = $data.mapKey; mapLayer = $data.mapLayer
    source = "Backup of $base before import on $(Get-Date -Format s)"
    regions = @($current | ForEach-Object {
        [ordered]@{ name = $_.name; terrainType = $_.terrainType; brushSize = $_.brushSize
                    points = @($_.points | ForEach-Object { [ordered]@{ x = $_.x; y = $_.y } }) }
    })
}
[IO.File]::WriteAllText($backup, ($backupData | ConvertTo-Json -Depth 6 -Compress), (New-Object Text.UTF8Encoding $false))
Write-Host "Backed up $($current.Count) existing strokes to $backup"

$action = if ($Append) { "ADD $($data.regions.Count) strokes to the $($current.Count) already there" }
          else { "REPLACE the $($current.Count) existing strokes with $($data.regions.Count)" }
if (-not $Yes) {
    $answer = Read-Host "This will $action on $base. Continue? [y/N]"
    if ($answer -notmatch '^[Yy]') { Write-Host 'Cancelled.'; return }
}

# --- Import -----------------------------------------------------------------------
$replace = if ($Append) { 'false' } else { 'true' }
$body = [Text.Encoding]::UTF8.GetBytes((Get-Content $File -Raw -Encoding UTF8))
try {
    $result = Invoke-RestMethod "$base/api/terrain-regions/import?replace=$replace" -Method Post `
        -ContentType 'application/json; charset=utf-8' -Body $body `
        -Headers @{ 'X-Map-Editor-Token' = $Token } -TimeoutSec 600
} catch {
    $status = $_.Exception.Response.StatusCode.value__
    if ($status -eq 403) { throw 'The map editor token was rejected. Check MAP_EDITOR_TOKEN on the server.' }
    if ($status -eq 503) { throw 'The server has no MAP_EDITOR_TOKEN set, so editing is turned off. Add it and redeploy.' }
    throw
}
Write-Host "Done: removed $($result.removed), imported $($result.imported) strokes." -ForegroundColor Green
Write-Host "To undo: .\scripts\import-terrain.ps1 -Url $base -File `"$backup`""
