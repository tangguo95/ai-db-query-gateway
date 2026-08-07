[CmdletBinding()]
param(
    [ValidateSet('start', 'stop', 'restart', 'status')]
    [string] $Action = 'status'
)

$ErrorActionPreference = 'Stop'
$scriptDirectory = $PSScriptRoot
$packagedServerJar = Join-Path $scriptDirectory 'gateway-server.jar'
$projectDirectory = if (-not [string]::IsNullOrWhiteSpace($env:GATEWAY_PROJECT_DIR)) {
    [IO.Path]::GetFullPath($env:GATEWAY_PROJECT_DIR)
} elseif (Test-Path -LiteralPath $packagedServerJar -PathType Leaf) {
    $scriptDirectory
} else {
    Split-Path -Parent $scriptDirectory
}
$serverJarCandidates = @(
    (Join-Path $projectDirectory 'gateway-server\target\gateway-server-0.1.0-SNAPSHOT.jar'),
    $packagedServerJar
)
$serverJar = $serverJarCandidates | Where-Object { Test-Path -LiteralPath $_ -PathType Leaf } | Select-Object -First 1
if ([string]::IsNullOrWhiteSpace($serverJar)) {
    $serverJar = $serverJarCandidates[0]
}
$port = if ([string]::IsNullOrWhiteSpace($env:GATEWAY_PORT)) { 8765 } else { [int]$env:GATEWAY_PORT }

function Resolve-DataDirectory {
    if (-not [string]::IsNullOrWhiteSpace($env:GATEWAY_DATA_DIR)) {
        return [IO.Path]::GetFullPath($env:GATEWAY_DATA_DIR)
    }
    $base = if ([string]::IsNullOrWhiteSpace($env:LOCALAPPDATA)) {
        Join-Path $env:USERPROFILE 'AppData\Local'
    } else {
        $env:LOCALAPPDATA
    }
    return Join-Path $base 'AI DB Query Gateway'
}

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

function Get-Paths {
    $dataDirectory = Resolve-DataDirectory
    $serviceDirectory = Join-Path $dataDirectory 'windows-service'
    New-Item -ItemType Directory -Path $serviceDirectory -Force | Out-Null
    return [pscustomobject]@{
        DataDirectory = $dataDirectory
        PidFile = Join-Path $serviceDirectory 'gateway.pid'
        LogFile = Join-Path $serviceDirectory 'gateway.log'
        ErrorLogFile = Join-Path $serviceDirectory 'gateway.error.log'
    }
}

function Get-RecordedProcess([object] $paths) {
    if (-not (Test-Path -LiteralPath $paths.PidFile -PathType Leaf)) {
        return $null
    }
    $rawPid = (Get-Content -Raw -LiteralPath $paths.PidFile).Trim()
    $process = Get-Process -Id ([int]$rawPid) -ErrorAction SilentlyContinue
    if ($null -eq $process) {
        Remove-Item -LiteralPath $paths.PidFile -Force -ErrorAction SilentlyContinue
        return $null
    }
    $commandLine = (Get-CimInstance Win32_Process -Filter "ProcessId = $($process.Id)").CommandLine
    if ($commandLine -notlike "*$serverJar*") {
        throw "PID file points to an unexpected process; refusing to stop PID $($process.Id)."
    }
    return $process
}

function Wait-Health {
    for ($attempt = 0; $attempt -lt 30; $attempt++) {
        try {
            $response = Invoke-WebRequest -UseBasicParsing -Uri "http://127.0.0.1:$port/actuator/health" -TimeoutSec 2
            if ($response.StatusCode -eq 200) {
                return $true
            }
        } catch {
            Start-Sleep -Seconds 1
        }
    }
    return $false
}

function Start-Gateway {
    $paths = Get-Paths
    if ($null -ne (Get-RecordedProcess $paths)) {
        Write-Host 'Gateway is already running.'
        return
    }
    if (-not (Test-Path -LiteralPath $serverJar -PathType Leaf)) {
        throw 'Server artifact is missing. Run .\scripts\build.ps1 first.'
    }
    $java = Resolve-Java21
    $arguments = @('-Dfile.encoding=UTF-8', '-Duser.timezone=Asia/Shanghai', '-jar', ('"' + $serverJar + '"'))
    $process = Start-Process -FilePath $java -ArgumentList $arguments -WorkingDirectory $projectDirectory `
        -RedirectStandardOutput $paths.LogFile -RedirectStandardError $paths.ErrorLogFile `
        -WindowStyle Hidden -PassThru
    Set-Content -LiteralPath $paths.PidFile -Value $process.Id -NoNewline
    if (-not (Wait-Health)) {
        Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue
        throw "Gateway did not become healthy. See $($paths.ErrorLogFile)."
    }
    Write-Host "Gateway started on http://127.0.0.1:$port (PID $($process.Id))."
    Write-Host "Logs: $($paths.LogFile)"
}

function Stop-Gateway {
    $paths = Get-Paths
    $process = Get-RecordedProcess $paths
    if ($null -eq $process) {
        Write-Host 'Gateway is not running.'
        return
    }
    Stop-Process -Id $process.Id -Force
    Remove-Item -LiteralPath $paths.PidFile -Force -ErrorAction SilentlyContinue
    Write-Host 'Gateway stopped.'
}

function Show-Status {
    $paths = Get-Paths
    $process = Get-RecordedProcess $paths
    if ($null -eq $process) {
        Write-Host 'Gateway process: stopped'
    } else {
        Write-Host "Gateway process: running (PID $($process.Id))"
    }
    try {
        $health = Invoke-WebRequest -UseBasicParsing -Uri "http://127.0.0.1:$port/actuator/health" -TimeoutSec 3
        Write-Host "Gateway health: $($health.StatusCode)"
    } catch {
        Write-Host 'Gateway health: unavailable'
    }
}

switch ($Action) {
    'start' { Start-Gateway }
    'stop' { Stop-Gateway }
    'restart' { Stop-Gateway; Start-Gateway }
    'status' { Show-Status }
}
