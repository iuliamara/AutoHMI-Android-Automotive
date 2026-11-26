param(
    [ValidateSet('before','after')][string]$Stage = 'after',
    [string]$Serial = 'emulator-5554',
    [ValidateSet('automotive','pixel9')][string]$Device = 'automotive',
    [ValidateRange(1.0,2.0)][double]$FontScale = 1.0,
    [switch]$LiveMaps,
    [switch]$SkipBuild,
    [switch]$RetrieveOnly
)
$ErrorActionPreference = 'Stop'
if ($Serial -notmatch '^emulator-\d+$') { throw 'Specify the exact emulator serial from adb devices.' }
$projectRoot = Split-Path -Parent $PSScriptRoot
$adb = Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'
Push-Location -LiteralPath $projectRoot
try {
    if (-not $SkipBuild -and -not $RetrieveOnly) {
        & .\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest --no-daemon --console=plain
        if ($LASTEXITCODE -ne 0) { throw 'Build failed.' }
        foreach ($apk in @('app/build/outputs/apk/debug/app-debug.apk','app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk')) {
            & $adb -s $Serial install --no-streaming -r -t $apk
            if ($LASTEXITCODE -ne 0) { throw 'APK update failed.' }
        }
    }
    # Automotive uses a foreground user distinct from the headless system user.
    $testUser = [int]((& $adb -s $Serial shell am get-current-user) | Select-Object -Last 1)
    $scaleText = $FontScale.ToString('0.0', [Globalization.CultureInfo]::InvariantCulture)
    $folder = "build/automotive-layout/$Device/$Stage-font$([int]($FontScale * 100))"
    New-Item -ItemType Directory -Force -Path $folder | Out-Null
    $mapMode = if ($LiveMaps) { 'true' } else { 'false' }
    if (-not $RetrieveOnly) {
    $result = & $adb -s $Serial shell am instrument --user $testUser -w -r `
        -e class 'com.example.autohmi.adaptive.AdaptiveLayoutTest#captureSyntheticScreens' `
        -e layoutCapture $Stage -e fontScale $scaleText -e liveMaps $mapMode `
        com.example.autohmi.test/com.example.autohmi.AutoHmiTestRunner
    $result | Set-Content "$folder/results.txt"
    $adbExitCode = $LASTEXITCODE
    $resultText = $result -join "`n"
    if ($adbExitCode -ne 0 -or $resultText -notmatch 'OK \(1 test\)' -or
        $resultText -match 'FAILURES!!!|INSTRUMENTATION_FAILED|INSTRUMENTATION_ABORTED') {
        throw "Synthetic capture test failed; see $folder/results.txt"
    }
    }
    & $adb -s $Serial exec-out run-as com.example.autohmi --user $testUser cat "cache/layout-$Stage-window.json" | Set-Content "$folder/window.json"
    foreach ($screen in @('dashboard','music-local','music-spotify','music-error','music-authorization','history','settings','navigation-dark','navigation-light')) {
        $process = New-Object System.Diagnostics.Process
        $process.StartInfo.FileName = $adb
        $process.StartInfo.Arguments = "-s $Serial exec-out run-as com.example.autohmi --user $testUser cat cache/layout-$Stage-$screen.png"
        $process.StartInfo.UseShellExecute = $false
        $process.StartInfo.CreateNoWindow = $true
        $process.StartInfo.RedirectStandardOutput = $true
        $process.StartInfo.RedirectStandardError = $true
        [void]$process.Start()
        $target = Join-Path (Get-Location) "$folder/$screen.png"
        $stream = [IO.File]::Create($target)
        try { $process.StandardOutput.BaseStream.CopyTo($stream) } finally { $stream.Dispose() }
        $process.WaitForExit()
        $bytes = [IO.File]::ReadAllBytes($target)
        if ($process.ExitCode -ne 0 -or $bytes.Length -lt 8 -or $bytes[0] -ne 137 -or $bytes[1] -ne 80) { throw "Missing test PNG: $screen" }
    }
    Write-Output "Synthetic application-window captures: $folder"
} finally { Pop-Location }
