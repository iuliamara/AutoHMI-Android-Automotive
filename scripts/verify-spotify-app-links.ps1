param(
    [Parameter(Mandatory = $true)]
    [string]$RedirectUri,
    [Parameter(Mandatory = $true)]
    [string[]]$Sha256Fingerprint
)

$ErrorActionPreference = 'Stop'
$uri = [Uri]$RedirectUri
if (-not $uri.IsAbsoluteUri -or $uri.Scheme -ne 'https' -or $uri.UserInfo -or $uri.Query -or
    $uri.Fragment -or -not $uri.IsDefaultPort -or $uri.AbsolutePath -eq '/' -or
    $uri.Host -eq 'localhost' -or $uri.Host -notmatch '\.') {
    throw 'Supply the actual HTTPS callback URL on your published host, without credentials, query or fragment.'
}
$fingerprints = @($Sha256Fingerprint | ForEach-Object {
    $fingerprint = $_.Trim().ToUpperInvariant()
    if ($fingerprint -notmatch '^(?:[0-9A-F]{2}:){31}[0-9A-F]{2}$') { throw 'Expected a certificate SHA-256, not SHA-1.' }
    $fingerprint
})
if ($fingerprints.Count -eq 0) { throw 'At least one certificate SHA-256 is required.' }

$associationUrl = $uri.GetLeftPart([UriPartial]::Authority) + '/.well-known/assetlinks.json'
# Do not accept a redirect, invalid TLS certificate, or HTML masquerading as JSON.
$response = Invoke-WebRequest -Uri $associationUrl -MaximumRedirection 0 -UseBasicParsing
if ([int]$response.StatusCode -ne 200 -or $response.Headers['Content-Type'] -notmatch '^application/json(?:\s*;|$)') {
    throw 'assetlinks.json must return HTTPS 200 and application/json without a redirect.'
}
$statements = ConvertFrom-Json -InputObject $response.Content
foreach ($fingerprint in $fingerprints) {
    $matchingStatements = @($statements | Where-Object {
        $_.relation -contains 'delegate_permission/common.handle_all_urls' -and
        $_.target.namespace -eq 'android_app' -and
        $_.target.package_name -eq 'com.example.autohmi' -and
        @($_.target.sha256_cert_fingerprints | ForEach-Object { $_.ToUpperInvariant() }) -contains $fingerprint
    })
    if ($matchingStatements.Count -eq 0) { throw 'The live association does not include the expected package/certificate pair.' }
}
$callback = Invoke-WebRequest -Uri $RedirectUri -MaximumRedirection 0 -UseBasicParsing
if ([int]$callback.StatusCode -ne 200 -or $callback.Headers['Content-Type'] -notmatch '^text/html(?:\s*;|$)') {
    throw 'The callback landing page must return HTTPS 200 text/html without a redirect.'
}
Write-Output 'Live HTTPS resources and certificate association match.'
Write-Output 'Next run Android domain verification on the device. This check is not proof of Android verification or Spotify authorization.'
