[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$projectDirectory = Split-Path -Parent $PSScriptRoot
$app = Join-Path $projectDirectory 'gateway-tray\build\app-image\AI DB Query Gateway Tray\AI DB Query Gateway Tray.exe'

if (-not (Test-Path -LiteralPath $app -PathType Leaf)) {
    & (Join-Path $PSScriptRoot 'build-tray.ps1')
    if ($LASTEXITCODE -ne 0) {
        exit $LASTEXITCODE
    }
}

Start-Process -FilePath $app -WorkingDirectory (Split-Path -Parent $app)
Write-Host "Tray app started: $app"
