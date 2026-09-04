param(
    [string]$ServerJar = "fabric-server-launch.jar",
    [string]$Java = "java",
    [string]$JvmArgs = "-Xms2G -Xmx2G"
)

$ErrorActionPreference = "Stop"
$serverRoot = $PSScriptRoot
$markerPath = Join-Path $serverRoot "server-reset-request.json"
$datapackSource = Join-Path $serverRoot "reset-datapacks"
New-Item -ItemType Directory -Path $datapackSource -Force | Out-Null

function Get-SafeWorldPath {
    $propertiesPath = Join-Path $serverRoot "server.properties"
    $levelName = "world"
    if (Test-Path -LiteralPath $propertiesPath) {
        $levelLine = Get-Content -LiteralPath $propertiesPath | Where-Object { $_ -match '^level-name=' } | Select-Object -Last 1
        if ($levelLine) { $levelName = $levelLine.Substring("level-name=".Length) }
    }
    if ([string]::IsNullOrWhiteSpace($levelName) -or [IO.Path]::IsPathRooted($levelName) -or $levelName.Contains("..") -or $levelName.Contains('/') -or $levelName.Contains('\')) {
        throw "Unsafe level-name '$levelName'"
    }
    $path = [IO.Path]::GetFullPath((Join-Path $serverRoot $levelName))
    if ([IO.Path]::GetDirectoryName($path) -ne [IO.Path]::GetFullPath($serverRoot).TrimEnd('\')) { throw "World path escaped the server folder" }
    return $path
}

function Install-ResetDatapacks {
    $worldPath = Get-SafeWorldPath
    $destination = Join-Path $worldPath "datapacks"
    New-Item -ItemType Directory -Path $destination -Force | Out-Null
    Get-ChildItem -LiteralPath $datapackSource -Force | Copy-Item -Destination $destination -Recurse -Force
}

while ($true) {
    Set-Location -LiteralPath $serverRoot
    Install-ResetDatapacks
    $javaArgs = @($JvmArgs -split '\s+' | Where-Object { $_ }) + @("-jar", $ServerJar, "nogui")
    & $Java @javaArgs

    if (-not (Test-Path -LiteralPath $markerPath)) { break }

    try {
        $marker = Get-Content -LiteralPath $markerPath -Raw | ConvertFrom-Json
        if ($marker.requestedBy -ne "server_reset_hardcore") { throw "Invalid reset marker" }

        $worldPath = Get-SafeWorldPath

        if (Test-Path -LiteralPath $worldPath) {
            Write-Host "Deleting old world: $worldPath"
            Remove-Item -LiteralPath $worldPath -Recurse -Force
        }
        Remove-Item -LiteralPath $markerPath -Force
        Write-Host "Starting reset #$($marker.resetNumber)..."
    } catch {
        Write-Error "Automatic reset aborted: $_"
        break
    }
}
