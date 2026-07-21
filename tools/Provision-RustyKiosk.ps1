[CmdletBinding()]
param(
  [Parameter(Mandatory)]
  [string]$Serial,

  [switch]$SkipBuild,

  [string]$AppApk,

  [string]$SetupHelperApk
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
$adbPath = (Get-Command adb -ErrorAction Stop).Source
$appPackage = 'io.github.mesmerprism.rustykiosk'
$helperPackage = 'io.github.mesmerprism.rustykiosk.setuphelper'
$writeSecureSettings = 'android.permission.WRITE_SECURE_SETTINGS'
$controlPermission = 'io.github.mesmerprism.rustykiosk.permission.SETUP_CONTROL'

if (-not $PSBoundParameters.ContainsKey('AppApk')) {
  $AppApk = Join-Path $repoRoot 'app\build\outputs\apk\debug\app-debug.apk'
}
if (-not $PSBoundParameters.ContainsKey('SetupHelperApk')) {
  $SetupHelperApk = Join-Path $repoRoot 'setup-helper\build\outputs\apk\debug\setup-helper-debug.apk'
}

$state = (& $adbPath -s $Serial get-state 2>&1).Trim()
if ($LASTEXITCODE -ne 0 -or $state -ne 'device') {
  throw "ADB device '$Serial' is not reachable."
}

if (-not $SkipBuild) {
  Push-Location $repoRoot
  try {
    & .\gradlew.bat :setup-helper:assembleDebug :app:assembleDebug
    if ($LASTEXITCODE -ne 0) {
      throw "Rusty Kiosk APK assembly failed with exit code $LASTEXITCODE."
    }
  } finally {
    Pop-Location
  }
}

foreach ($apk in @($SetupHelperApk, $AppApk)) {
  if (-not (Test-Path -LiteralPath $apk -PathType Leaf)) {
    throw "APK not found: $apk"
  }
}

$helperInstall = & $adbPath -s $Serial install -r -d $SetupHelperApk 2>&1
if ($LASTEXITCODE -ne 0 -or ($helperInstall -join "`n") -notmatch 'Success') {
  throw "Setup helper install failed: $($helperInstall -join ' ')"
}

$grantOutput = & $adbPath -s $Serial shell pm grant $helperPackage $writeSecureSettings 2>&1
if ($LASTEXITCODE -ne 0) {
  throw "Setup authority grant failed: $($grantOutput -join ' ')"
}

$appInstall = & $adbPath -s $Serial install -r -d $AppApk 2>&1
if ($LASTEXITCODE -ne 0 -or ($appInstall -join "`n") -notmatch 'Success') {
  throw "Rusty Kiosk install failed: $($appInstall -join ' ')"
}

$helperPackageState = (& $adbPath -s $Serial shell dumpsys package $helperPackage 2>&1) -join "`n"
$helperGrantPattern = [Regex]::Escape($writeSecureSettings) + ': granted=true'
if ($LASTEXITCODE -ne 0 -or $helperPackageState -notmatch $helperGrantPattern) {
  throw 'Rusty Kiosk Setup does not hold its one-time provisioned settings authority.'
}

$mainPackageState = (& $adbPath -s $Serial shell dumpsys package $appPackage 2>&1) -join "`n"
$mainControlPattern = [Regex]::Escape($controlPermission) + ': granted=true'
if ($LASTEXITCODE -ne 0 -or $mainPackageState -notmatch $mainControlPattern) {
  throw 'The main app does not hold the same-signer setup control permission.'
}

$launch = & $adbPath -s $Serial shell am start -W -n "$appPackage/.RustyKioskActivity" 2>&1
if ($LASTEXITCODE -ne 0 -or ($launch -join "`n") -match 'Error:|SecurityException|Permission Denial') {
  throw "Rusty Kiosk launch failed: $($launch -join ' ')"
}

Write-Output 'Rusty Kiosk and its dedicated setup helper are installed and provisioned.'
Write-Output 'No setting was enabled automatically. Use the panel or typed debug CLI for each explicit opt-in.'
