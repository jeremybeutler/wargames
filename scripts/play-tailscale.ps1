<#
.SYNOPSIS
    Hosts the game with a permanent Tailscale Funnel link
    (https://<computer>.<tailnet>.ts.net). Same as: play.ps1 -Tunnel tailscale

.EXAMPLE
    .\scripts\play-tailscale.ps1
.EXAMPLE
    .\scripts\play-tailscale.ps1 -SkipBuild
#>
param(
    [switch]$SkipBuild,
    [int]$Port = 8080
)

& (Join-Path $PSScriptRoot 'play.ps1') -Tunnel tailscale -SkipBuild:$SkipBuild -Port $Port
