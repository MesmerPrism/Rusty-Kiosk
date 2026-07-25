[CmdletBinding()]
param(
  [Parameter(Mandatory = $true)]
  [string]$Serial,

  [ValidateSet('missing', 'signer-mismatch', 'trusted-launch')]
  [string]$ExpectedState,

  [string]$ApkPath,
  [string]$LauncherPackage = 'io.github.mesmerprism.rustykiosk.launcher',
  [string]$TargetPackage = 'io.github.mesmerprism.rustykiosk',
  [string]$ExpectedLauncherApkSha256,
  [string]$ExpectedLauncherSignerSha256,
  [string]$ExpectedInstallerPackage,
  [switch]$RequireNonShellInstallSource,
  [switch]$SkipInstall,
  [int]$WaitSeconds = 3,
  [string]$OutputRoot
)

$ErrorActionPreference = 'Stop'

if ($PSVersionTable.PSEdition -ne 'Core' -or $PSVersionTable.PSVersion -lt [version]'7.6') {
  throw 'Launcher smoke validation requires PowerShell 7.6 or newer through pwsh.'
}
if ($WaitSeconds -lt 1 -or $WaitSeconds -gt 15) {
  throw 'WaitSeconds must be between 1 and 15.'
}

$repoRoot = Split-Path -Parent $PSScriptRoot
if ([string]::IsNullOrWhiteSpace($OutputRoot)) {
  $OutputRoot = Join-Path $repoRoot 'local-artifacts\launcher-smoke'
}

$deviceRows = @(& adb devices)
if (@($deviceRows | Where-Object { $_ -match "^$([regex]::Escape($Serial))\s+device$" }).Count -ne 1) {
  throw "Quest serial is not connected and authorized: $Serial"
}

if (-not $SkipInstall) {
  if ([string]::IsNullOrWhiteSpace($ApkPath)) {
    throw 'ApkPath is required unless -SkipInstall is used.'
  }
  $ApkPath = (Resolve-Path $ApkPath).Path
}
$inputApkSha256 =
  if ($SkipInstall) {
    $null
  } else {
    (Get-FileHash -Algorithm SHA256 -LiteralPath $ApkPath).Hash.ToLowerInvariant()
  }

function Get-ForegroundSummary {
  param([Parameter(Mandatory = $true)][string]$DeviceSerial)

  $rows = @(& adb -s $DeviceSerial shell dumpsys activity activities)
  (
    $rows |
      Where-Object {
        $_ -match 'topResumedActivity|ResumedActivity:|mCurrentFocus=|mFocusedApp='
      } |
      Select-Object -Last 16
  ) -join "`n"
}

function Get-ApkCertificateDigest {
  param([Parameter(Mandatory = $true)][string]$Path)

  $apkSigner = Get-Command 'apksigner.bat' -ErrorAction SilentlyContinue
  if ($null -eq $apkSigner) {
    $sdkRoot = if (-not [string]::IsNullOrWhiteSpace($env:ANDROID_SDK_ROOT)) {
      $env:ANDROID_SDK_ROOT
    } else {
      $env:ANDROID_HOME
    }
    if (-not [string]::IsNullOrWhiteSpace($sdkRoot)) {
      $apkSigner =
        Get-ChildItem -LiteralPath (Join-Path $sdkRoot 'build-tools') `
          -Recurse -Filter 'apksigner.bat' -ErrorAction SilentlyContinue |
          Sort-Object FullName -Descending |
          Select-Object -First 1
    }
  }
  if ($null -eq $apkSigner) {
    throw 'apksigner is required to capture installed launcher identity.'
  }
  $apkSignerPath = if ($apkSigner.PSObject.Properties.Name -contains 'Source') {
    $apkSigner.Source
  } else {
    $apkSigner.FullName
  }
  $output = @(& $apkSignerPath verify --print-certs $Path 2>&1)
  if ($LASTEXITCODE -ne 0) {
    throw "Installed launcher signature verification failed: $($output -join ' ')"
  }
  $matches = [regex]::Matches(
    (($output | Where-Object { $_ -notmatch 'Source Stamp' }) -join "`n"),
    '(?im)certificate\s+SHA-?256\s+digest\s*:\s*([0-9a-fA-F:\- ]{64,128})'
  )
  $digests = @(
    $matches |
      ForEach-Object {
        ($_.Groups[1].Value -replace '[^0-9a-fA-F]', '').ToLowerInvariant()
      } |
      Sort-Object -Unique
  )
  if ($digests.Count -ne 1 -or $digests[0].Length -ne 64) {
    throw "Expected exactly one installed launcher certificate digest, got: $($digests -join ', ')"
  }
  $digests[0]
}

$runId = (Get-Date).ToUniversalTime().ToString('yyyyMMdd-HHmmss')
$runDir = Join-Path $OutputRoot "$runId-$Serial-$ExpectedState"
New-Item -ItemType Directory -Force -Path $runDir | Out-Null

$preLauncherPath = @(& adb -s $Serial shell pm path $LauncherPackage 2>$null)
$preLauncherInstalled = @($preLauncherPath | Where-Object { $_ -match '^package:' }).Count -gt 0
$targetDump = @(& adb -s $Serial shell dumpsys package $TargetPackage 2>$null) -join "`n"
$targetVersion = [regex]::Match($targetDump, '(?m)^\s*versionName=(.+)$').Groups[1].Value.Trim()
$foregroundBefore = Get-ForegroundSummary -DeviceSerial $Serial

if (-not $SkipInstall) {
  $installOutput = @(& adb -s $Serial install -r -d -g $ApkPath 2>&1)
  if ($LASTEXITCODE -ne 0 -or ($installOutput -join "`n") -notmatch 'Success') {
    throw "Launcher install failed: $($installOutput -join ' ')"
  }
}

$activity =
  "$LauncherPackage/io.github.mesmerprism.rustykiosk.launcher.RustyKioskLauncherActivity"
& adb -s $Serial shell am force-stop $LauncherPackage | Out-Null
& adb -s $Serial logcat -c
$launchOutput = @(& adb -s $Serial shell am start -W -n $activity 2>&1)
if ($LASTEXITCODE -ne 0 -or ($launchOutput -join "`n") -notmatch 'Status:\s+ok') {
  throw "Launcher Activity failed to start: $($launchOutput -join ' ')"
}
Start-Sleep -Seconds $WaitSeconds

$foregroundAfter = Get-ForegroundSummary -DeviceSerial $Serial
$logcat = @(& adb -s $Serial logcat -d -v threadtime)
$logcatPath = Join-Path $runDir 'logcat.txt'
$logcat | Set-Content -LiteralPath $logcatPath -Encoding utf8NoBOM
$marker = "state=$ExpectedState"
$logText = $logcat -join "`n"
$markerFound = $logText -match [regex]::Escape($marker)
$immersiveFocusPattern =
  "(?m)(Top Activity Changed.*topActivity now is|topActivityName|Changing in focus immersive app.*to).*" +
  [regex]::Escape($TargetPackage)
$focusExpected =
  if ($ExpectedState -eq 'trusted-launch') {
    ($foregroundAfter -match [regex]::Escape($TargetPackage)) -or
      ($logText -match $immersiveFocusPattern)
  } else {
    $foregroundAfter -match [regex]::Escape($LauncherPackage)
  }
$fatalCount =
  @($logcat | Where-Object { $_ -match 'FATAL EXCEPTION|Fatal signal' }).Count

& adb -s $Serial shell am force-stop $LauncherPackage | Out-Null
$postLauncherPath = @(& adb -s $Serial shell pm path $LauncherPackage 2>$null)
$postLauncherInstalled = @($postLauncherPath | Where-Object { $_ -match '^package:' }).Count -gt 0
$installedApkPath = $null
$installedApkSha256 = $null
$installedSignerSha256 = $null
$launcherVersionCode = $null
$launcherVersionName = $null
$installerPackage = $null
$initiatingPackage = $null
$originatingPackage = $null
$packageSource = $null
if ($postLauncherInstalled) {
  $baseApkRows = @(
    $postLauncherPath |
      Where-Object { $_ -match '^package:' } |
      ForEach-Object { $_.Substring('package:'.Length).Trim() }
  )
  $deviceBaseApk = @($baseApkRows | Where-Object { $_ -match '/base\.apk$' }) | Select-Object -First 1
  if ([string]::IsNullOrWhiteSpace($deviceBaseApk)) {
    $deviceBaseApk = $baseApkRows | Select-Object -First 1
  }
  if ([string]::IsNullOrWhiteSpace($deviceBaseApk)) {
    throw 'Unable to resolve the installed launcher base APK.'
  }
  $installedApkPath = Join-Path $runDir 'installed-launcher.apk'
  $pullOutput = @(& adb -s $Serial pull $deviceBaseApk $installedApkPath 2>&1)
  if ($LASTEXITCODE -ne 0 -or -not (Test-Path -LiteralPath $installedApkPath -PathType Leaf)) {
    throw "Unable to pull the installed launcher APK: $($pullOutput -join ' ')"
  }
  $installedApkSha256 =
    (Get-FileHash -Algorithm SHA256 -LiteralPath $installedApkPath).Hash.ToLowerInvariant()
  $installedSignerSha256 = Get-ApkCertificateDigest -Path $installedApkPath

  $launcherDump = @(& adb -s $Serial shell dumpsys package $LauncherPackage 2>$null) -join "`n"
  $launcherVersionCode =
    [regex]::Match($launcherDump, '(?m)^\s*versionCode=(\d+)').Groups[1].Value
  $launcherVersionName =
    [regex]::Match($launcherDump, '(?m)^\s*versionName=(.+)$').Groups[1].Value.Trim()
  $installerPackage =
    [regex]::Match($launcherDump, '(?m)^\s*installerPackageName=(.+)$').Groups[1].Value.Trim()
  $initiatingPackage =
    [regex]::Match($launcherDump, '(?m)^\s*initiatingPackageName=(.+)$').Groups[1].Value.Trim()
  $originatingPackage =
    [regex]::Match($launcherDump, '(?m)^\s*originatingPackageName=(.+)$').Groups[1].Value.Trim()
  $packageSource =
    [regex]::Match($launcherDump, '(?m)^\s*packageSource=(.+)$').Groups[1].Value.Trim()
}

$identityExpected = $postLauncherInstalled
if (-not $SkipInstall) {
  $identityExpected = $identityExpected -and $installedApkSha256 -ceq $inputApkSha256
}
if (-not [string]::IsNullOrWhiteSpace($ExpectedLauncherApkSha256)) {
  $identityExpected =
    $identityExpected -and
      $installedApkSha256 -ceq $ExpectedLauncherApkSha256.ToLowerInvariant()
}
if (-not [string]::IsNullOrWhiteSpace($ExpectedLauncherSignerSha256)) {
  $identityExpected =
    $identityExpected -and
      $installedSignerSha256 -ceq $ExpectedLauncherSignerSha256.ToLowerInvariant()
}
if (-not [string]::IsNullOrWhiteSpace($ExpectedInstallerPackage)) {
  $identityExpected = $identityExpected -and $installerPackage -ceq $ExpectedInstallerPackage
}
if ($RequireNonShellInstallSource) {
  $identityExpected =
    $identityExpected -and
      $initiatingPackage -notin @('', 'null', 'com.android.shell')
}

$summary = [ordered]@{
  schema = 'rusty.kiosk.launcher.device_smoke.v2'
  captured_at_utc = (Get-Date).ToUniversalTime().ToString('o')
  serial = $Serial
  launcher = [ordered]@{
    package = $LauncherPackage
    activity = $activity
    input_apk = if ($SkipInstall) { $null } else { $ApkPath }
    input_apk_sha256 = $inputApkSha256
    installed_before = $preLauncherInstalled
    installed_after = $postLauncherInstalled
    cleanup_requires_explicit_uninstall =
      (-not $SkipInstall -and -not $preLauncherInstalled -and $postLauncherInstalled)
    installed_identity = [ordered]@{
      pulled_apk = $installedApkPath
      apk_sha256 = $installedApkSha256
      signer_sha256 = $installedSignerSha256
      version_code = $launcherVersionCode
      version_name = $launcherVersionName
      installer_package = $installerPackage
      initiating_package = $initiatingPackage
      originating_package = $originatingPackage
      package_source = $packageSource
      expected = $identityExpected
    }
  }
  target = [ordered]@{
    package = $TargetPackage
    version_name = $targetVersion
  }
  expected_state = $ExpectedState
  marker_found = $markerFound
  foreground_before = $foregroundBefore
  foreground_after = $foregroundAfter
  focus_expected = $focusExpected
  bounded_fatal_count = $fatalCount
  result =
    if ($markerFound -and $focusExpected -and $identityExpected -and $fatalCount -eq 0) {
      'pass'
    } else {
      'fail'
    }
  artifacts = [ordered]@{
    logcat = $logcatPath
  }
}
$summaryPath = Join-Path $runDir 'smoke-summary.json'
$summary | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $summaryPath -Encoding utf8NoBOM
$summary | ConvertTo-Json -Depth 8

if ($summary.result -ne 'pass') {
  throw "Launcher device smoke failed. Summary: $summaryPath"
}
