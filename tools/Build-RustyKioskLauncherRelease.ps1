[CmdletBinding()]
param(
  [string]$MetadataPath
)

$ErrorActionPreference = 'Stop'

if ($PSVersionTable.PSEdition -ne 'Core' -or $PSVersionTable.PSVersion -lt [version]'7.6') {
  throw 'Release builds require PowerShell 7.6 or newer through pwsh.'
}

$repoRoot = Split-Path -Parent $PSScriptRoot
$productionPackage = 'io.github.mesmerprism.rustykiosk.launcher'
$productionTarget = 'io.github.mesmerprism.rustykiosk'

if (
  -not [string]::IsNullOrWhiteSpace($env:RUSTY_KIOSK_LAUNCHER_APPLICATION_ID) -and
  $env:RUSTY_KIOSK_LAUNCHER_APPLICATION_ID -cne $productionPackage
) {
  throw 'Release builds reject a non-production launcher application id.'
}
if (
  -not [string]::IsNullOrWhiteSpace($env:RUSTY_KIOSK_LAUNCHER_TARGET_PACKAGE) -and
  $env:RUSTY_KIOSK_LAUNCHER_TARGET_PACKAGE -cne $productionTarget
) {
  throw 'Release builds reject a non-production Rusty Kiosk target package.'
}

$signingNames = @(
  'RUSTY_KIOSK_LAUNCHER_KEYSTORE_PATH',
  'RUSTY_KIOSK_LAUNCHER_KEYSTORE_PASSWORD',
  'RUSTY_KIOSK_LAUNCHER_KEY_ALIAS',
  'RUSTY_KIOSK_LAUNCHER_KEY_PASSWORD'
)
$missingSigning = @(
  $signingNames |
    Where-Object { [string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($_)) }
)
if ($missingSigning.Count -gt 0) {
  throw "Release signing is not configured: $($missingSigning -join ', ')"
}
if (-not (Test-Path -LiteralPath $env:RUSTY_KIOSK_LAUNCHER_KEYSTORE_PATH -PathType Leaf)) {
  throw 'The configured launcher keystore does not exist.'
}

function Find-AndroidBuildTool {
  param([Parameter(Mandatory = $true)][string]$Name)

  $command = Get-Command $Name -ErrorAction SilentlyContinue
  if ($null -ne $command) {
    return $command.Source
  }
  $sdkRoot = if (-not [string]::IsNullOrWhiteSpace($env:ANDROID_SDK_ROOT)) {
    $env:ANDROID_SDK_ROOT
  } else {
    $env:ANDROID_HOME
  }
  if ([string]::IsNullOrWhiteSpace($sdkRoot)) {
    throw "Android SDK root is not configured while locating $Name."
  }
  $tool =
    Get-ChildItem -LiteralPath (Join-Path $sdkRoot 'build-tools') -Recurse -Filter $Name |
      Sort-Object FullName -Descending |
      Select-Object -First 1
  if ($null -eq $tool) {
    throw "$Name was not found under the Android SDK build-tools directory."
  }
  $tool.FullName
}

$aapt2 = Find-AndroidBuildTool -Name 'aapt2.exe'
$apksigner = Find-AndroidBuildTool -Name 'apksigner.bat'

Push-Location $repoRoot
try {
  & .\gradlew.bat --console=plain `
    :launcher:testDebugUnitTest `
    :launcher:lintRelease `
    :launcher:assembleRelease
  if ($LASTEXITCODE -ne 0) {
    throw "Launcher release build failed with exit code $LASTEXITCODE."
  }
} finally {
  Pop-Location
}

$apk =
  Get-ChildItem -LiteralPath (Join-Path $repoRoot 'launcher\build\outputs\apk\release') `
    -Filter '*.apk' -File |
    Sort-Object LastWriteTimeUtc -Descending |
    Select-Object -First 1
if ($null -eq $apk) {
  throw 'Launcher release APK was not produced.'
}

$signatureOutput = @(& $apksigner verify --verbose --print-certs $apk.FullName 2>&1)
if ($LASTEXITCODE -ne 0 -or ($signatureOutput -join "`n") -notmatch 'Verifies') {
  throw 'Launcher release APK signature verification failed.'
}
$badging = (& $aapt2 dump badging $apk.FullName) -join "`n"
$permissions = (& $aapt2 dump permissions $apk.FullName) -join "`n"
$manifest = (& $aapt2 dump xmltree --file AndroidManifest.xml $apk.FullName) -join "`n"
$archiveEntries = (& jar tf $apk.FullName) -join "`n"

$checks = [ordered]@{
  package_id = $badging -match "package: name='io\.github\.mesmerprism\.rustykiosk\.launcher'"
  application_label = $badging -match "application-label:'Rusty Kiosk Launcher'"
  version_code = $badging -match "versionCode='[1-9][0-9]*'"
  version_name = $badging -match "versionName='[0-9]+\.[0-9]+\.[0-9]+'"
  install_location_auto = $badging -match "install-location:'auto'"
  min_sdk_supported = $badging -match "minSdkVersion:'3[0-4]'"
  target_sdk_supported = $badging -match "targetSdkVersion:'3[2-6]'"
  launch_activity =
    $badging -match
      "launchable-activity: name='io\.github\.mesmerprism\.rustykiosk\.launcher\.RustyKioskLauncherActivity'"
  head_tracking_required = $badging -match "uses-feature: name='android\.hardware\.vr\.headtracking'"
  release_not_debuggable = $badging -notmatch 'application-debuggable'
  category_2d = $manifest -match 'com\.oculus\.intent\.category\.2D'
  category_launcher = $manifest -match 'android\.intent\.category\.LAUNCHER'
  no_vr_category = $manifest -notmatch 'com\.oculus\.intent\.category\.VR$'
  excluded_from_recents = $manifest -match 'excludeFromRecents.*=true'
  supported_devices = $manifest -match 'com\.oculus\.supportedDevices'
  exact_target_query = $manifest -match 'io\.github\.mesmerprism\.rustykiosk'
  no_declared_permissions = $permissions -notmatch 'uses-permission:'
  no_background_components = $manifest -notmatch '(?m)^\s*E: (service|provider|receiver)'
  no_native_libraries = $archiveEntries -notmatch '(?m)^lib/'
  signature_verified = $true
}
$failed = @(
  $checks.GetEnumerator() |
    Where-Object { -not [bool]$_.Value } |
    ForEach-Object { $_.Key }
)
if ($failed.Count -gt 0) {
  throw "Launcher release APK checks failed: $($failed -join ', ')"
}

$signerMatches = [regex]::Matches(
  (($signatureOutput | Where-Object { $_ -notmatch 'Source Stamp' }) -join "`n"),
  '(?im)certificate\s+SHA-?256\s+digest\s*:\s*([0-9a-fA-F:\- ]{64,128})'
)
$signerDigests = @(
  $signerMatches |
    ForEach-Object {
      ($_.Groups[1].Value -replace '[^0-9a-fA-F]', '').ToLowerInvariant()
    } |
    Sort-Object -Unique
)
if ($signerDigests.Count -ne 1 -or $signerDigests[0].Length -ne 64) {
  throw "Expected exactly one launcher signing-certificate digest, got: $($signerDigests -join ', ')"
}

$metadata = [ordered]@{
  schema = 'rusty.kiosk.launcher.release_build.v1'
  created_at_utc = (Get-Date).ToUniversalTime().ToString('o')
  apk = [IO.Path]::GetFullPath($apk.FullName)
  sha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $apk.FullName).Hash.ToLowerInvariant()
  signer_sha256 = $signerDigests[0]
  package = $productionPackage
  target_package = $productionTarget
  version_code = [int]([regex]::Match($badging, "versionCode='([0-9]+)'").Groups[1].Value)
  version_name = [regex]::Match($badging, "versionName='([^']+)'").Groups[1].Value
  checks = $checks
}

if (-not [string]::IsNullOrWhiteSpace($MetadataPath)) {
  $metadataFullPath = [IO.Path]::GetFullPath($MetadataPath)
  New-Item -ItemType Directory -Force -Path (Split-Path -Parent $metadataFullPath) | Out-Null
  $metadata | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $metadataFullPath -Encoding utf8NoBOM
}

$metadata | ConvertTo-Json -Depth 8
