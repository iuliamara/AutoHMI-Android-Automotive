param(
    [string]$Adb = '',
    [string]$Serial = ''
)

$ErrorActionPreference = 'Stop'
if (-not $Adb) {
    if ($env:ANDROID_HOME) {
        $Adb = Join-Path $env:ANDROID_HOME 'platform-tools\adb.exe'
    } else {
        $Adb = (Get-Command adb -ErrorAction Stop).Source
    }
}

$deviceArguments = @()
if ($Serial) { $deviceArguments = @('-s', $Serial) }
$projectRoot = Split-Path -Parent $PSScriptRoot
Push-Location -LiteralPath $projectRoot
try {
    & .\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest --no-daemon --console=plain
    if ($LASTEXITCODE -ne 0) { throw 'Application/test APK build failed.' }

    # Update in place; never clear, uninstall or reset existing app/device data.
    & $Adb @deviceArguments install --no-streaming -r app/build/outputs/apk/debug/app-debug.apk
    if ($LASTEXITCODE -ne 0) { throw 'Application installation failed.' }
    & $Adb @deviceArguments install --no-streaming -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
    if ($LASTEXITCODE -ne 0) { throw 'Test APK installation failed.' }

    $foregroundUser = [int]((& $Adb @deviceArguments shell am get-current-user) | Select-Object -Last 1)
    & $Adb @deviceArguments shell am instrument --user $foregroundUser -w -r `
        com.example.autohmi.test/com.example.autohmi.AutoHmiTestRunner |
        Tee-Object -FilePath build/instrumentation-results.txt | Write-Output
    $adbExitCode = $LASTEXITCODE
    # ADB may exit successfully even when the JUnit runner reports failed tests.
    $resultText = Get-Content -Raw build/instrumentation-results.txt
    if ($adbExitCode -ne 0 -or $resultText -notmatch 'OK \(\d+ tests?\)' -or
        $resultText -match 'FAILURES!!!|INSTRUMENTATION_FAILED|INSTRUMENTATION_ABORTED') {
        throw 'Device tests failed. See build/instrumentation-results.txt.'
    }
} finally {
    Pop-Location
}
