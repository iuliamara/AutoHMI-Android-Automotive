#requires -Version 7.0
<#
.SYNOPSIS
Read-only Android/Spotify environment diagnostics. Never attempts account login.
.DESCRIPTION
Requires PowerShell 7. Uses bounded, allowlisted ADB commands. No Logcat, screenshots,
UI dumps, accounts, app storage, browser data, credentials or authentication responses.
Writes sanitized JSON/text only to the project's ignored build/diagnostics/spotify/.
#>
param(
    [string]$Adb = '',
    [string]$Serial = '',
    [switch]$SkipNetworkProbes
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$outputRoot = Join-Path $projectRoot 'build/diagnostics/spotify'

function Invoke-BoundedCommand {
    param([string]$Executable, [string[]]$Arguments, [int]$TimeoutMs = 15000)
    $start = [Diagnostics.ProcessStartInfo]::new()
    $start.FileName = $Executable
    $start.UseShellExecute = $false
    $start.CreateNoWindow = $true
    $start.RedirectStandardOutput = $true
    $start.RedirectStandardError = $true
    foreach ($argument in $Arguments) { $start.ArgumentList.Add($argument) }
    $process = [Diagnostics.Process]::new()
    $process.StartInfo = $start
    try {
        [void]$process.Start()
        $stdout = $process.StandardOutput.ReadToEndAsync()
        $stderr = $process.StandardError.ReadToEndAsync()
        if (-not $process.WaitForExit($TimeoutMs)) {
            $process.Kill($true)
            return [pscustomobject]@{ Success = $false; Text = ''; TimedOut = $true }
        }
        # Never persist raw stderr: it can include local paths or command context.
        [void]$stderr.GetAwaiter().GetResult()
        return [pscustomobject]@{ Success = ($process.ExitCode -eq 0); Text = $stdout.GetAwaiter().GetResult().Trim(); TimedOut = $false }
    } finally { $process.Dispose() }
}

function Find-Adb {
    if ($Adb) {
        if (-not (Test-Path -LiteralPath $Adb -PathType Leaf)) { throw 'Specified ADB executable not found.' }
        return (Resolve-Path -LiteralPath $Adb).Path
    }
    $candidates = @(
        $(if ($env:ANDROID_SDK_ROOT) { Join-Path $env:ANDROID_SDK_ROOT 'platform-tools/adb.exe' }),
        $(if ($env:ANDROID_HOME) { Join-Path $env:ANDROID_HOME 'platform-tools/adb.exe' }),
        $(if ($env:LOCALAPPDATA) { Join-Path $env:LOCALAPPDATA 'Android/Sdk/platform-tools/adb.exe' }),
        $(if (Get-Command adb -ErrorAction SilentlyContinue) { (Get-Command adb).Source })
    )
    foreach ($candidate in $candidates) {
        if ($candidate -and (Test-Path -LiteralPath $candidate -PathType Leaf)) { return $candidate }
    }
    throw 'ADB not found. Set ANDROID_SDK_ROOT/ANDROID_HOME or pass -Adb. No software was installed.'
}

$adbPath = Find-Adb
function Invoke-DeviceShell([string]$Command) {
    Invoke-BoundedCommand -Executable $adbPath -Arguments @('-s', $Serial, 'shell', $Command)
}
function Read-Scalar([string]$Command, [string]$Pattern = '^[A-Za-z0-9_.+:/() ,;=\[\]-]{0,240}$') {
    $result = Invoke-DeviceShell $Command
    if (-not $result.Success) { return 'unavailable' }
    if ($result.Text -notmatch $Pattern) { return 'unavailable' }
    if (-not $result.Text) { return 'unset' }
    return $result.Text
}

$inventory = Invoke-BoundedCommand -Executable $adbPath -Arguments @('devices')
if (-not $inventory.Success) { throw 'Unable to query connected Android devices.' }
$devices = @($inventory.Text -split '\r?\n' | ForEach-Object {
    if ($_ -match '^([A-Za-z0-9._:-]+)\s+(device|offline|unauthorized)\s*$') {
        # Physical device identifiers are deliberately not retained.
        [pscustomobject]@{ Id = $matches[1]; State = $matches[2]; Emulator = $matches[1] -match '^emulator-\d+$' }
    }
})
if (-not $Serial) {
    $ready = @($devices | Where-Object { $_.State -eq 'device' -and $_.Emulator })
    if ($ready.Count -ne 1) { throw 'Select one connected emulator with -Serial; none or several are available. No device changed.' }
    $Serial = $ready[0].Id
}
if ($Serial -notmatch '^[A-Za-z0-9._:-]+$' -or -not ($devices | Where-Object { $_.Id -eq $Serial -and $_.State -eq 'device' })) {
    throw 'Selected device is not connected/authorized. Authorize ADB manually; this script does not change security settings.'
}
$emulator = $Serial -match '^emulator-\d+$'
$issues = [Collections.Generic.List[string]]::new()
$build = [ordered]@{}
foreach ($property in @(
    'ro.product.model', 'ro.product.device', 'ro.product.cpu.abi', 'ro.product.cpu.abilist',
    'ro.build.version.release', 'ro.build.version.sdk', 'ro.build.version.security_patch',
    'ro.build.id', 'ro.build.type', 'ro.build.tags', 'ro.build.fingerprint', 'ro.kernel.qemu',
    'ro.boot.verifiedbootstate', 'ro.boot.flash.locked', 'sys.boot_completed'
)) { $build[$property] = Read-Scalar "getprop $property" }

$sdkRoot = Split-Path -Parent (Split-Path -Parent $adbPath)
$emulatorVersion = 'unavailable'
$emulatorExecutable = Join-Path $sdkRoot 'emulator/emulator.exe'
if (Test-Path -LiteralPath $emulatorExecutable) {
    $version = Invoke-BoundedCommand -Executable $emulatorExecutable -Arguments @('-version')
    if ($version.Text -match '(?m)^Android emulator version ([0-9.]+) \(build_id (\d+)\)') {
        $emulatorVersion = "$($matches[1]) (build $($matches[2]))"
    }
}
$avd = [ordered]@{ Name = 'unavailable'; Tag = 'unavailable'; Abi = 'unavailable'; PlayStoreEnabled = 'unavailable' }
if ($emulator) {
    $nameResult = Invoke-BoundedCommand -Executable $adbPath -Arguments @('-s', $Serial, 'emu', 'avd', 'name')
    $avdName = ($nameResult.Text -split '\r?\n' | Select-Object -First 1).Trim()
    if ($nameResult.Success -and $avdName -match '^[A-Za-z0-9_.-]+$') {
        $avd.Name = $avdName
        $avdRoots = @(
            $(if ($env:ANDROID_AVD_HOME) { $env:ANDROID_AVD_HOME }),
            $(if ($env:ANDROID_USER_HOME) { Join-Path $env:ANDROID_USER_HOME 'avd' }),
            $(if ($env:USERPROFILE) { Join-Path $env:USERPROFILE '.android/avd' })
        )
        foreach ($avdRoot in $avdRoots) {
            if (-not $avdRoot) { continue }
            $config = Join-Path $avdRoot "$avdName.avd/config.ini"
            if (Test-Path -LiteralPath $config) {
                foreach ($line in [IO.File]::ReadLines($config)) {
                    if ($line -match '^tag.id=(google_apis_playstore|google_apis|default)$') { $avd.Tag = $matches[1] }
                    if ($line -match '^abi.type=([A-Za-z0-9_]+)$') { $avd.Abi = $matches[1] }
                    if ($line -match '^PlayStore.enabled=(true|false)$') { $avd.PlayStoreEnabled = $matches[1] }
                }
                break
            }
        }
    }
}

$packageNames = @('com.spotify.music', 'com.google.android.gms', 'com.android.vending', 'com.google.android.webview', 'com.android.chrome', 'com.example.autohmi')
$packages = @()
foreach ($package in $packageNames) {
    # Package metadata only. Raw dump is never emitted or saved.
    $dump = Invoke-DeviceShell "dumpsys package $package"
    $installed = $dump.Success -and $dump.Text -match '(?m)^\s*versionCode=\d+'
    $versionCode = 'unavailable'; $versionName = 'unavailable'; $minSdk = 'unavailable'; $targetSdk = 'unavailable'; $abi = 'unavailable'
    $enabledState = 'unavailable'; $internetRequested = $false; $internetGranted = 'unavailable'; $networkStateRequested = $false
    if ($installed) {
        if ($dump.Text -match '(?m)^\s*versionCode=(\d+)\s+minSdk=(\d+)\s+targetSdk=(\d+)') {
            $versionCode = $matches[1]; $minSdk = $matches[2]; $targetSdk = $matches[3]
        }
        if ($dump.Text -match '(?m)^\s*versionName=([A-Za-z0-9._()+ \[\]-]{1,140})\s*$') { $versionName = $matches[1].Trim() }
        if ($dump.Text -match '(?m)^\s*primaryCpuAbi=([A-Za-z0-9_-]+)\s*$') { $abi = $matches[1] }
        if ($dump.Text -match '(?m)^\s*User 0:.*?enabled=(\d+)') { $enabledState = $matches[1] }
        $internetRequested = $dump.Text -match '(?m)^\s*android.permission.INTERNET\s*$'
        $networkStateRequested = $dump.Text -match '(?m)^\s*android.permission.ACCESS_NETWORK_STATE\s*$'
        if ($dump.Text -match 'android.permission.INTERNET: granted=(true|false)') { $internetGranted = $matches[1] }
        if ($enabledState -in @('2','3','4')) { $issues.Add("$package is disabled for Android user 0.") }
        if ($package -eq 'com.spotify.music' -and $internetGranted -eq 'false') { $issues.Add('Spotify INTERNET permission is not granted.') }
    } else { $issues.Add("Package missing or metadata unavailable: $package") }
    $packages += [pscustomobject]@{ Package = $package; Installed = $installed; Version = $versionName; VersionCode = $versionCode; MinSdk = $minSdk; TargetSdk = $targetSdk; PrimaryAbi = $abi; EnabledState = $enabledState; InternetRequested = $internetRequested; InternetGranted = $internetGranted; NetworkStateRequested = $networkStateRequested }
}
# Query only the allowlisted installer package, not install-session/account information.
$installerResult = Invoke-DeviceShell 'pm list packages -i com.spotify.music'
$spotifyInstaller = if ($installerResult.Success -and $installerResult.Text -match 'installer=([A-Za-z0-9_.]+)') { $matches[1] } else { 'unavailable' }
$webView = Invoke-DeviceShell 'dumpsys webviewupdate'
$webViewProvider = 'unavailable'
if ($webView.Success -and $webView.Text -match 'Current WebView package \(name, version\):\s*\(([A-Za-z0-9_.]+),\s*([A-Za-z0-9._-]+)\)') {
    $webViewProvider = "$($matches[1]) $($matches[2])"
}

$clock = [ordered]@{
    DeviceUtc = Read-Scalar 'date -u +%Y-%m-%dT%H:%M:%SZ'
    Timezone = Read-Scalar 'getprop persist.sys.timezone'
    AutomaticTime = Read-Scalar 'settings get global auto_time'
    AutomaticTimezone = Read-Scalar 'settings get global auto_time_zone'
    HostDeviceSkewSeconds = 'unavailable'
}
$deviceEpoch = Read-Scalar 'date +%s' '^\d{10,12}$'
if ($deviceEpoch -match '^\d{10,12}$') {
    $clock.HostDeviceSkewSeconds = [Math]::Abs([DateTimeOffset]::UtcNow.ToUnixTimeSeconds() - [long]$deviceEpoch)
    if ($clock.HostDeviceSkewSeconds -gt 120) { $issues.Add('Device time differs from host by more than two minutes; compare both against a trusted clock manually.') }
}

# Proxy values and private DNS hostnames are reduced on-device to presence/mode only.
$proxyProbe = 'case "$(settings get global http_proxy)" in ""|null|:0) echo absent;; *) echo configured;; esac'
$privateDnsProbe = 'case "$(settings get global private_dns_mode)" in off) echo off;; opportunistic) echo automatic;; hostname) echo strict;; *) echo unknown;; esac'
$network = [ordered]@{
    AirplaneMode = Read-Scalar 'settings get global airplane_mode_on'
    HttpProxy = Read-Scalar $proxyProbe
    PrivateDnsMode = Read-Scalar $privateDnsProbe
    LegacyDnsPropertiesPresent = Read-Scalar 'if [ -n "$(getprop net.dns1)$(getprop net.dns2)" ]; then echo present; else echo absent; fi'
    ActiveInternetNetworks = $null
    ValidatedInternetNetworks = $null
    CaptivePortalNetworks = $null
    VpnNetworks = $null
    DnsConfiguredOnActiveLink = $null
    SpotifyDnsResolution = 'not tested'
    IcmpReachability = 'not tested'
    PublicHttpsProbe = 'unavailable (no curl)'
}
# Reduce connectivity records on-device. No SSIDs, addresses, proxy credentials,
# VPN identities or DNS query history cross ADB into this script.
$connectivityProbe = @'
dumpsys connectivity | grep '^ *NetworkAgentInfo{' | {
 internet=0; validated=0; captive=0; vpn=0; dns=0
 while IFS= read -r line; do
  case "$line" in *INTERNET*) internet=$((internet+1));; esac
  case "$line" in *VALIDATED*) validated=$((validated+1));; esac
  case "$line" in *CAPTIVE_PORTAL*) captive=$((captive+1));; esac
  case "$line" in *"Transports: VPN"*) vpn=$((vpn+1));; esac
  if printf '%s\n' "$line" | grep -q 'DnsAddresses: *\[[^]]*[0-9]'; then dns=1; fi
 done
 echo "$internet,$validated,$captive,$vpn,$dns"
}
'@
$connectivity = Invoke-DeviceShell $connectivityProbe
if ($connectivity.Success -and $connectivity.Text -match '^(\d+),(\d+),(\d+),(\d+),([01])$') {
    $network.ActiveInternetNetworks = [int]$matches[1]
    $network.ValidatedInternetNetworks = [int]$matches[2]
    $network.CaptivePortalNetworks = [int]$matches[3]
    $network.VpnNetworks = [int]$matches[4]
    $network.DnsConfiguredOnActiveLink = $matches[5] -eq '1'
} else {
    $issues.Add('Active connectivity metadata was unavailable; null counts are unknown, not evidence of no network.')
}
if (-not $SkipNetworkProbes) {
    # Stateless public probes. No browser/Spotify session, cookies, authorization endpoint,
    # token request or account information. ICMP failure is not evidence of TLS failure.
    $dns = Invoke-DeviceShell 'ping -c 1 -W 3 accounts.spotify.com >/dev/null 2>&1; if [ $? -eq 0 ]; then echo resolved_and_pinged; else echo unresolved_or_no_icmp_reply; fi'
    if ($dns.Success -and $dns.Text -match '^(resolved_and_pinged|unresolved_or_no_icmp_reply)$') { $network.SpotifyDnsResolution = $dns.Text }
    $ping = Invoke-DeviceShell 'ping -c 1 -W 3 1.1.1.1 >/dev/null 2>&1; if [ $? -eq 0 ]; then echo replied; else echo no_reply; fi'
    if ($ping.Success -and $ping.Text -match '^(replied|no_reply)$') { $network.IcmpReachability = $ping.Text }
    $https = Invoke-DeviceShell 'if command -v curl >/dev/null 2>&1; then curl -q --noproxy "*" --max-time 8 --silent --output /dev/null --write-out "%{http_code}" https://accounts.spotify.com/; else echo unavailable; fi'
    if ($https.Success -and $https.Text -match '^\d{3}$') { $network.PublicHttpsProbe = "HTTP $($https.Text) (fresh unauthenticated curl; no content/headers saved)" }
    elseif ($https.Text -eq '000') { $network.PublicHttpsProbe = 'TLS/network probe failed; no detailed error collected' }
}
if ($network.AirplaneMode -eq '1') { $issues.Add('Airplane mode is enabled; actual network availability must be checked separately.') }
if ($network.HttpProxy -eq 'configured') { $issues.Add('An HTTP proxy is configured; its identity/value was not collected.') }
if ($network.PrivateDnsMode -eq 'strict') { $issues.Add('Strict private DNS is configured; verify reachability manually without changing it automatically.') }
if ($network.CaptivePortalNetworks -gt 0) { $issues.Add('A captive-portal network was reported.') }
if ($build['sys.boot_completed'] -ne '1') { $issues.Add('Device boot completion was not confirmed.') }
$appLinks = Invoke-DeviceShell 'pm get-app-links --user 0 com.example.autohmi'
$callbackDomainState = 'unavailable'
if ($appLinks.Success -and $appLinks.Text -match '(?m)^\s*iuliamara\.github\.io:\s*([A-Za-z0-9_-]+)\s*$') { $callbackDomainState = $matches[1] }
$callbackUserSelection = 'unavailable'
if ($appLinks.Success) {
    $section = ''
    foreach ($line in ($appLinks.Text -split '\r?\n')) {
        if ($line -match '^\s*(Enabled|Disabled):\s*$') { $section = $matches[1] }
        if ($line -match '^\s*iuliamara\.github\.io\s*$' -and $section) { $callbackUserSelection = $section.ToLowerInvariant() }
    }
}
$linkHandlingAllowed = 'unavailable'
if ($appLinks.Success -and $appLinks.Text -match 'Verification link handling allowed:\s*(true|false)') { $linkHandlingAllowed = $matches[1] }
if ($linkHandlingAllowed -eq 'false') { $issues.Add('AutoHMI link handling is disallowed for user 0; inspect Open by default settings manually.') }
# The CLI's Enabled/Disabled lists describe manual selection membership. A verified
# host can appear under Disabled when it has not been manually selected; this alone
# is not evidence that effective verified-link handling is blocked.
$resolution = Invoke-DeviceShell 'cmd package resolve-activity --brief -a android.intent.action.VIEW -c android.intent.category.BROWSABLE -d https://iuliamara.github.io/spotify/callback.html'
$callbackHandler = 'unavailable'
if ($resolution.Success -and $resolution.Text -match '(?m)^([A-Za-z0-9_.]+/[A-Za-z0-9_.$]+)\s*$') { $callbackHandler = $matches[1] }
if ($issues.Count -eq 0) { $issues.Add('No obvious anomaly in collected metadata; this does not diagnose Spotify account authentication.') }

$result = [ordered]@{
    CapturedUtc = [DateTimeOffset]::UtcNow.ToString('u')
    Privacy = 'Metadata only. No login, Logcat, browser data, accounts, app storage, credentials, codes, cookies or tokens.'
    Inventory = @{ ConnectedCount = $devices.Count; ReadyEmulatorCount = @($devices | Where-Object { $_.Emulator -and $_.State -eq 'device' }).Count; Selected = $(if ($emulator) { $Serial } else { 'physical device (identifier omitted)' }) }
    EmulatorVersion = $emulatorVersion
    Avd = $avd
    Android = $build
    Packages = $packages
    SpotifyInstaller = $spotifyInstaller
    ActiveWebView = $webViewProvider
    Clock = $clock
    Network = $network
    AutoHmiCallbackDomainState = $callbackDomainState
    AutoHmiCallbackUserSelection = $callbackUserSelection
    AutoHmiLinkHandlingAllowed = $linkHandlingAllowed
    AutoHmiCallbackResolvedActivity = $callbackHandler
    PlayProtectCertification = 'Unknown: package presence/build tags are not certification. Check Play Store > profile > Settings > About manually.'
    Anomalies = @($issues)
    Limitations = @('Installed versions are not a live store-update check.', 'No Spotify login attempt or internal authentication error was observed.', 'No Logcat collected; any login-time capture requires separate informed approval.', 'No DRM/Spotify Web Playback capability was exercised.', 'No applications, settings, security configuration or device data changed.')
}
New-Item -ItemType Directory -Path $outputRoot -Force | Out-Null
$jsonPath = Join-Path $outputRoot 'environment.json'
[IO.File]::WriteAllText($jsonPath, ($result | ConvertTo-Json -Depth 8), [Text.UTF8Encoding]::new($false))
$summary = @(
    'Spotify environment diagnostic summary',
    "Selected: $($result.Inventory.Selected)",
    "Emulator: $emulatorVersion; AVD: $($avd.Name); image tag: $($avd.Tag)",
    "Android $($build['ro.build.version.release']) / API $($build['ro.build.version.sdk']); ABI $($build['ro.product.cpu.abilist'])",
    "WebView: $webViewProvider; Spotify installer: $spotifyInstaller",
    "UTC host/device skew (seconds): $($clock.HostDeviceSkewSeconds)",
    "Callback domain state: $callbackDomainState; handling allowed: $linkHandlingAllowed; raw manual-selection list: $callbackUserSelection",
    'A verified host in the Disabled manual-selection list is not by itself a blocked-link diagnosis.',
    '', 'Packages:'
) + @($packages | ForEach-Object { "$($_.Package): installed=$($_.Installed); version=$($_.Version); ABI=$($_.PrimaryAbi); enabledState=$($_.EnabledState); INTERNET=$($_.InternetGranted)" }) + @(
    '', 'Network (sanitized):', ($network | ConvertTo-Json -Compress),
    '', 'Observations:'
) + @($issues | ForEach-Object { "- $_" }) + @('', $result.PlayProtectCertification, '', $result.Privacy)
[IO.File]::WriteAllLines((Join-Path $outputRoot 'summary.txt'), [string[]]$summary, [Text.UTF8Encoding]::new($false))
$summary | Write-Output
Write-Output "Sanitized results saved under: $outputRoot"
