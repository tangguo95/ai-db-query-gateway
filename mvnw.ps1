[CmdletBinding()]
param(
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]] $MavenArguments
)

$ErrorActionPreference = 'Stop'

$mavenVersion = '3.9.11'
$scriptDirectory = Split-Path -Parent $MyInvocation.MyCommand.Path
$cacheDirectory = if ([string]::IsNullOrWhiteSpace($env:MAVEN_WRAPPER_CACHE)) {
    Join-Path $scriptDirectory '.mvn-dist'
} else {
    $env:MAVEN_WRAPPER_CACHE
}
$mavenHome = Join-Path $cacheDirectory "apache-maven-$mavenVersion"
$archivePath = Join-Path $cacheDirectory "apache-maven-$mavenVersion-bin.zip"
$checksumPath = "$archivePath.sha512"
$downloadBase = "https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/$mavenVersion"

if (-not (Test-Path -LiteralPath (Join-Path $mavenHome 'bin\mvn.cmd'))) {
    New-Item -ItemType Directory -Path $cacheDirectory -Force | Out-Null
    if (-not (Test-Path -LiteralPath $archivePath)) {
        Invoke-WebRequest -Uri "$downloadBase/apache-maven-$mavenVersion-bin.zip" -OutFile $archivePath
    }
    if (-not (Test-Path -LiteralPath $checksumPath)) {
        Invoke-WebRequest -Uri "$downloadBase/apache-maven-$mavenVersion-bin.zip.sha512" -OutFile $checksumPath
    }

    $expectedHash = ((Get-Content -Raw -LiteralPath $checksumPath).Trim() -split '\s+')[0].ToLowerInvariant()
    $sha512 = [Security.Cryptography.SHA512]::Create()
    try {
        $actualHash = [BitConverter]::ToString(
            $sha512.ComputeHash([IO.File]::ReadAllBytes($archivePath))).Replace('-', '').ToLowerInvariant()
    } finally {
        $sha512.Dispose()
    }
    if ($expectedHash -ne $actualHash) {
        throw 'Maven distribution checksum verification failed.'
    }

    $temporaryExtract = Join-Path $cacheDirectory '.maven-extract'
    if (Test-Path -LiteralPath $temporaryExtract) {
        Remove-Item -LiteralPath $temporaryExtract -Recurse -Force
    }
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    [IO.Compression.ZipFile]::ExtractToDirectory($archivePath, $temporaryExtract)
    $extractedHome = Join-Path $temporaryExtract "apache-maven-$mavenVersion"
    if (-not (Test-Path -LiteralPath (Join-Path $extractedHome 'bin\mvn.cmd'))) {
        throw 'Downloaded Maven archive has an unexpected layout.'
    }
    if (Test-Path -LiteralPath $mavenHome) {
        Remove-Item -LiteralPath $mavenHome -Recurse -Force
    }
    Move-Item -LiteralPath $extractedHome -Destination $mavenHome
    Remove-Item -LiteralPath $temporaryExtract -Recurse -Force
}

& (Join-Path $mavenHome 'bin\mvn.cmd') @MavenArguments
exit $LASTEXITCODE
