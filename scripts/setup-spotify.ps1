$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$destination = Join-Path $projectRoot 'app/libs/spotify-app-remote-release-0.8.0.aar'
$officialUrl = 'https://github.com/spotify/android-sdk/releases/download/v0.8.0-appremote_v2.1.0-auth/spotify-app-remote-release-0.8.0.aar'
$expectedHash = 'b5a6dd880eaf01f63a871cba9ef7af77c341f8a94ffc8fdf2e9021f9a9d4c198'

Add-Type -AssemblyName System.IO.Compression.FileSystem
function Test-SpotifyAar([string]$path) {
    $actualHash = (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($actualHash -ne $expectedHash) { throw 'Spotify AAR SHA-256 mismatch. The file will not be used.' }
    $archive = [System.IO.Compression.ZipFile]::OpenRead($path)
    try {
        if (-not $archive.GetEntry('AndroidManifest.xml') -or -not $archive.GetEntry('classes.jar')) {
            throw 'Spotify download is not a valid Android AAR.'
        }
    } finally { $archive.Dispose() }
}

if (Test-Path -LiteralPath $destination) {
    Test-SpotifyAar $destination
    Write-Output "Existing Spotify App Remote 0.8.0 verified. SHA-256: $expectedHash"
    exit 0
}

New-Item -ItemType Directory -Path (Split-Path -Parent $destination) -Force | Out-Null
$temporaryDownload = "$destination.download"
try {
    Invoke-WebRequest -Uri $officialUrl -OutFile $temporaryDownload
    Test-SpotifyAar $temporaryDownload
    Move-Item -LiteralPath $temporaryDownload -Destination $destination
    Write-Output "Downloaded official Spotify App Remote 0.8.0. SHA-256: $expectedHash"
} finally {
    if (Test-Path -LiteralPath $temporaryDownload) { Remove-Item -LiteralPath $temporaryDownload }
}
