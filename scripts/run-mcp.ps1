[CmdletBinding()]
param(
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]] $McpArguments
)

$ErrorActionPreference = 'Stop'
$projectDirectory = Split-Path -Parent $PSScriptRoot
$serverJar = Join-Path $projectDirectory 'gateway-server\target\gateway-server-0.1.0-SNAPSHOT.jar'
$mcpJar = Join-Path $projectDirectory 'gateway-mcp\target\gateway-mcp.jar'
$account = 'mcp:codex:default'

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

if (-not (Test-Path -LiteralPath $mcpJar -PathType Leaf)) {
    throw 'MCP artifact is missing. Run .\scripts\build.ps1 first.'
}

$java = Resolve-Java21
if ([string]::IsNullOrWhiteSpace($env:AI_DB_GATEWAY_TOKEN)) {
    if (-not (Test-Path -LiteralPath $serverJar -PathType Leaf)) {
        throw 'Server artifact is missing. Run .\scripts\build.ps1 first.'
    }
    $readArguments = @(
        '-Dfile.encoding=UTF-8',
        '-Duser.timezone=Asia/Shanghai',
        '-jar',
        $serverJar,
        '--gateway.secret-store-cli=get',
        "--gateway.secret-store-account=$account"
    )
    $token = (& $java @readArguments | Out-String).Trim()
    if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($token)) {
        throw 'The scoped MCP token is missing from Windows DPAPI. Run .\scripts\configure-mcp-token.ps1 first.'
    }
    $env:AI_DB_GATEWAY_TOKEN = $token
}

if ([string]::IsNullOrWhiteSpace($env:AI_DB_GATEWAY_URL)) {
    $env:AI_DB_GATEWAY_URL = 'http://127.0.0.1:8765'
}

$arguments = @('-Dfile.encoding=UTF-8', '-Duser.timezone=Asia/Shanghai', '-jar', $mcpJar)
$arguments += $McpArguments
& $java @arguments
exit $LASTEXITCODE
