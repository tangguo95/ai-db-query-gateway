[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$projectDirectory = Split-Path -Parent $PSScriptRoot
$serverJar = Join-Path $projectDirectory 'gateway-server\target\gateway-server-0.1.0-SNAPSHOT.jar'
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

if (-not (Test-Path -LiteralPath $serverJar -PathType Leaf)) {
    throw 'Server artifact is missing. Run .\scripts\build.ps1 first.'
}

$secureToken = Read-Host '请输入网关作用域 Token（输入内容不会显示）' -AsSecureString
$tokenPointer = [IntPtr]::Zero
$plainToken = $null
try {
    $tokenPointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secureToken)
    $plainToken = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($tokenPointer)
    if ($plainToken -notmatch '^gwy_[A-Za-z0-9_-]+$') {
        throw 'Token 格式不正确，未写入 Windows DPAPI。'
    }
    $java = Resolve-Java21
    $arguments = @(
        '-Dfile.encoding=UTF-8',
        '-Duser.timezone=Asia/Shanghai',
        '-jar',
        $serverJar,
        '--gateway.secret-store-cli=put',
        "--gateway.secret-store-account=$account"
    )
    $plainToken | & $java @arguments
    if ($LASTEXITCODE -ne 0) {
        throw 'Token 写入 Windows DPAPI 失败。'
    }
    Write-Host 'MCP Token 已安全写入 Windows DPAPI（当前 Windows 用户）。'
} finally {
    if ($tokenPointer -ne [IntPtr]::Zero) {
        [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($tokenPointer)
    }
    $plainToken = $null
}
