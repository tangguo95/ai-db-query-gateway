[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$projectDirectory = Split-Path -Parent $PSScriptRoot

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

$java = Resolve-Java21
Write-Host "Using Java: $java"
Push-Location $projectDirectory
try {
    & (Join-Path $projectDirectory 'mvnw.cmd') clean verify
    if ($LASTEXITCODE -ne 0) {
        exit $LASTEXITCODE
    }
} finally {
    Pop-Location
}

& (Join-Path $projectDirectory 'scripts\build-tray.ps1') -SkipMaven
if ($LASTEXITCODE -ne 0) {
    exit $LASTEXITCODE
}

Write-Host 'Build completed: gateway-server, gateway-mcp, and the Windows tray app are ready.'
