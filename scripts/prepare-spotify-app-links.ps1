param(
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[A-Za-z0-9](?:[A-Za-z0-9-]{0,37}[A-Za-z0-9])?$')]
    [string]$GitHubUsername,
    [Parameter(Mandatory = $true)]
    [string[]]$Sha256Fingerprint
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$templateRoot = Join-Path $projectRoot 'hosting/spotify-callback'
$outputRoot = Join-Path $projectRoot 'build/spotify-app-links/site'
$fingerprints = @($Sha256Fingerprint | ForEach-Object {
    $fingerprint = $_.Trim().ToUpperInvariant()
    if ($fingerprint -notmatch '^(?:[0-9A-F]{2}:){31}[0-9A-F]{2}$') {
        throw 'Each certificate SHA-256 must contain 32 colon-separated hexadecimal bytes. Use signingReport; do not use SHA-1 here.'
    }
    $fingerprint
} | Select-Object -Unique)
if ($fingerprints.Count -eq 0) { throw 'At least one actual signing certificate SHA-256 is required.' }

# Generated output stays under the workspace's ignored build directory.
New-Item -ItemType Directory -Path (Join-Path $outputRoot '.well-known') -Force | Out-Null
New-Item -ItemType Directory -Path (Join-Path $outputRoot 'spotify') -Force | Out-Null
Copy-Item -LiteralPath (Join-Path $templateRoot 'index.html') -Destination (Join-Path $outputRoot 'index.html')
Copy-Item -LiteralPath (Join-Path $templateRoot 'spotify/callback.html') -Destination (Join-Path $outputRoot 'spotify/callback.html')
Copy-Item -LiteralPath (Join-Path $templateRoot '.nojekyll') -Destination (Join-Path $outputRoot '.nojekyll')
$association = @(@{
    relation = @('delegate_permission/common.handle_all_urls')
    target = @{
        namespace = 'android_app'
        package_name = 'com.example.autohmi'
        sha256_cert_fingerprints = $fingerprints
    }
})
$json = ConvertTo-Json -InputObject $association -Depth 6
# Write UTF-8 without a BOM consistently in Windows PowerShell 5.1 and PowerShell 7.
[System.IO.File]::WriteAllText(
    (Join-Path $outputRoot '.well-known/assetlinks.json'),
    $json + [Environment]::NewLine,
    [System.Text.UTF8Encoding]::new($false)
)

$hostname = $GitHubUsername.ToLowerInvariant() + '.github.io'
Write-Output "Prepared files: $outputRoot"
Write-Output "Publish these files at the ROOT of your $hostname user site, including .nojekyll and .well-known."
Write-Output "Candidate after successful deployment: https://$hostname/spotify/callback.html"
Write-Output 'This does not create a site, establish domain ownership, or configure Spotify. Verify the live host first.'
