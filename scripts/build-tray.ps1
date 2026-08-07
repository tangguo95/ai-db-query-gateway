[CmdletBinding()]
param(
    [switch] $SkipMaven
)

$ErrorActionPreference = 'Stop'
$projectDirectory = Split-Path -Parent $PSScriptRoot
$trayDirectory = Join-Path $projectDirectory 'gateway-tray'
$buildDirectory = Join-Path $trayDirectory 'build'
$inputDirectory = Join-Path $buildDirectory 'input'
$outputDirectory = Join-Path $buildDirectory 'app-image'

function Resolve-Java21 {
    $candidates = @()
    if (-not [string]::IsNullOrWhiteSpace($env:JAVA_HOME)) {
        $candidates += Join-Path $env:JAVA_HOME 'bin\java.exe'
    }
    $command = Get-Command java.exe -ErrorAction SilentlyContinue
    if ($null -ne $command) {
        $candidates += $command.Source
    }
    foreach ($candidate in ($candidates | Select-Object -Unique)) {
        if (Test-Path -LiteralPath $candidate) {
            $versionCommand = '"' + $candidate + '" -version 2>&1'
            $versionOutput = (& cmd.exe /d /c $versionCommand | Out-String)
            if ($versionOutput -match 'version "21') {
                return $candidate
            }
        }
    }
    throw 'Java 21 was not found. Set JAVA_HOME to a Java 21 installation.'
}

function Reset-BuildDirectory([string] $path) {
    $fullPath = [IO.Path]::GetFullPath($path)
    $fullBuildDirectory = [IO.Path]::GetFullPath($buildDirectory).TrimEnd('\') + '\'
    if (-not $fullPath.StartsWith($fullBuildDirectory, [StringComparison]::OrdinalIgnoreCase)) {
        throw "Refusing to clean a path outside the tray build directory: $fullPath"
    }
    if (Test-Path -LiteralPath $fullPath) {
        Remove-Item -LiteralPath $fullPath -Recurse -Force
    }
    New-Item -ItemType Directory -Path $fullPath -Force | Out-Null
}

$java = Resolve-Java21
$javaHome = Split-Path -Parent (Split-Path -Parent $java)
$jpackage = Join-Path $javaHome 'bin\jpackage.exe'
if (-not (Test-Path -LiteralPath $jpackage -PathType Leaf)) {
    throw "jpackage was not found under Java 21: $jpackage"
}

Push-Location $projectDirectory
try {
    if (-not $SkipMaven) {
        & (Join-Path $projectDirectory 'mvnw.cmd') '-pl' 'gateway-tray' '-DskipTests' 'package'
        if ($LASTEXITCODE -ne 0) {
            exit $LASTEXITCODE
        }
    }

    $trayJar = Join-Path $trayDirectory 'target\gateway-tray.jar'
    $serverJar = Join-Path $projectDirectory 'gateway-server\target\gateway-server-0.1.0-SNAPSHOT.jar'
    $serviceScript = Join-Path $projectDirectory 'scripts\windows-service.ps1'
    foreach ($required in @($trayJar, $serverJar, $serviceScript)) {
        if (-not (Test-Path -LiteralPath $required -PathType Leaf)) {
            throw "Required build artifact is missing: $required"
        }
    }

    Reset-BuildDirectory $inputDirectory
    Reset-BuildDirectory $outputDirectory
    Copy-Item -LiteralPath $trayJar -Destination (Join-Path $inputDirectory 'gateway-tray.jar')
    Copy-Item -LiteralPath $serverJar -Destination (Join-Path $inputDirectory 'gateway-server.jar')
    Copy-Item -LiteralPath $serviceScript -Destination (Join-Path $inputDirectory 'windows-service.ps1')

    $arguments = @(
        '--type', 'app-image',
        '--name', 'AI DB Query Gateway Tray',
        '--app-version', '0.1.0',
        '--vendor', 'AI DB Query Gateway',
        '--description', 'Windows tray manager for AI DB Query Gateway',
        '--dest', $outputDirectory,
        '--input', $inputDirectory,
        '--main-jar', 'gateway-tray.jar',
        '--main-class', 'com.tangguo.gateway.tray.GatewayTrayApplication',
        '--add-modules', 'java.desktop,java.net.http',
        '--java-options', '-Dfile.encoding=UTF-8',
        '--java-options', '-Duser.language=zh',
        '--java-options', '-Duser.country=CN'
    )
    & $jpackage @arguments
    if ($LASTEXITCODE -ne 0) {
        exit $LASTEXITCODE
    }

    $app = Join-Path $outputDirectory 'AI DB Query Gateway Tray\AI DB Query Gateway Tray.exe'
    Write-Host "Tray app ready: $app"
} finally {
    Pop-Location
}
