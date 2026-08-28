<#
.SYNOPSIS
    Looks up the Fabric toolchain versions for a Minecraft release and prints the
    matching gradle.properties lines.

.DESCRIPTION
    Loom resolves its dependencies while Gradle is still configuring the project, so a
    wrong version in gradle.properties fails the build before any Gradle task can run.
    This script therefore stands on its own and never touches Gradle.

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File fabric-versions.ps1
    Prints the four lines so you can paste them in yourself.

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File fabric-versions.ps1 -Write
    Prints them and updates gradle.properties in place.
#>
param(
    [string] $MinecraftVersion,
    [switch] $Write
)

$ErrorActionPreference = 'Stop'
# Windows PowerShell 5.1 still negotiates TLS 1.0 by default, which these APIs refuse.
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

$propsFile = Join-Path $PSScriptRoot 'gradle.properties'

# Property access on a collection member-enumerates, handing back every value rather than
# one. Peel until we reach a scalar so a lookup always yields a single version.
function Get-First($Value) {
    while ($Value -is [array]) {
        if ($Value.Count -eq 0) { return $null }
        $Value = $Value[0]
    }
    return $Value
}

# A version must be one whitespace-free token. Anything else means the shape of a response
# was not what we assumed, and writing it into gradle.properties would only produce a
# confusing "Could not find" later.
function Assert-Version([string] $Name, $Value) {
    $text = [string](Get-First $Value)
    if (-not $text) { throw "$Name lookup produced nothing." }
    if ($text -match '\s') { throw "$Name lookup produced multiple values: $text" }
    return $text
}

if (-not $MinecraftVersion) {
    if (Test-Path $propsFile) {
        $found = Select-String -Path $propsFile -Pattern '^\s*minecraft_version\s*=\s*(.+)$' |
                 Select-Object -First 1
        if ($found) { $MinecraftVersion = $found.Matches[0].Groups[1].Value.Trim() }
    }
}
if (-not $MinecraftVersion) { $MinecraftVersion = '1.21.11' }

Write-Host "Looking up the Fabric toolchain for Minecraft $MinecraftVersion ..."

function Get-Json([string] $Url) {
    return Invoke-RestMethod -Uri $Url -TimeoutSec 20 `
        -Headers @{ 'User-Agent' = 'mirage-setup (fabric mod build script)' }
}

$yarn = $null
$loader = $null
$api = $null
$problems = @()

try {
    $builds = Get-Json "https://meta.fabricmc.net/v2/versions/yarn/$MinecraftVersion"
    # Newest build first.
    $yarn = Assert-Version 'yarn_mappings' $builds.version
} catch {
    $problems += "yarn lookup failed: $($_.Exception.Message)"
}

try {
    $loaders = Get-Json "https://meta.fabricmc.net/v2/versions/loader/$MinecraftVersion"
    # Member-enumerate both fields into parallel arrays and pair them up by index, which
    # avoids assuming anything about how PowerShell unrolled the response.
    $loaderVersions = @($loaders.loader.version)
    $loaderStable = @($loaders.loader.stable)

    $picked = $null
    for ($i = 0; $i -lt $loaderVersions.Count; $i++) {
        if ($i -lt $loaderStable.Count -and $loaderStable[$i]) { $picked = $loaderVersions[$i]; break }
    }
    # Fall back to the newest loader if none is flagged stable.
    if (-not $picked -and $loaderVersions.Count -gt 0) { $picked = $loaderVersions[0] }

    $loader = Assert-Version 'loader_version' $picked
} catch {
    $problems += "loader lookup failed: $($_.Exception.Message)"
}

try {
    $games = [uri]::EscapeDataString("[""$MinecraftVersion""]")
    $url = "https://api.modrinth.com/v2/project/fabric-api/version" +
           "?game_versions=$games&loaders=%5B%22fabric%22%5D"
    $releases = Get-Json $url
    $api = Assert-Version 'fabric_version' $releases.version_number
} catch {
    $problems += "Fabric API lookup failed: $($_.Exception.Message)"
}

Write-Host ""
Write-Host "minecraft_version=$MinecraftVersion"
Write-Host "yarn_mappings=$yarn"
Write-Host "loader_version=$loader"
Write-Host "fabric_version=$api"
Write-Host ""

if ($problems.Count -gt 0) {
    Write-Host "Some lookups failed:" -ForegroundColor Yellow
    foreach ($problem in $problems) { Write-Host "  - $problem" -ForegroundColor Yellow }
    Write-Host "Get the missing values by hand from https://fabricmc.net/develop/" -ForegroundColor Yellow
    Write-Host ""
}

if (-not $Write) {
    Write-Host "Paste those four lines into gradle.properties, or re-run with -Write to do it for you."
    return
}

if (-not ($yarn -and $loader -and $api)) {
    Write-Host "Not touching gradle.properties: at least one lookup failed." -ForegroundColor Red
    exit 1
}
if (-not (Test-Path $propsFile)) {
    Write-Host "Not touching gradle.properties: $propsFile does not exist." -ForegroundColor Red
    exit 1
}

function Set-Prop([string] $Text, [string] $Name, [string] $Value) {
    $pattern = "(?m)^\s*$([regex]::Escape($Name))\s*=.*$"
    if ($Text -match $pattern) {
        return [regex]::Replace($Text, $pattern, "$Name=$Value")
    }
    # The line was missing entirely -- add it rather than silently doing nothing.
    return $Text.TrimEnd() + "`r`n$Name=$Value`r`n"
}

$text = Get-Content -Path $propsFile -Raw
$text = Set-Prop $text 'minecraft_version' $MinecraftVersion
$text = Set-Prop $text 'yarn_mappings'     $yarn
$text = Set-Prop $text 'loader_version'    $loader
$text = Set-Prop $text 'fabric_version'    $api

# ASCII on purpose: PowerShell 5.1 writes a BOM for UTF8, and a BOM corrupts the first
# line of a .properties file as far as Gradle is concerned.
Set-Content -Path $propsFile -Value $text -Encoding ASCII -NoNewline

Write-Host "gradle.properties updated. Now run: gradlew build" -ForegroundColor Green
