[CmdletBinding()]
param(
  [string]$Distribution = 'Store',
  [string]$MetadataPath
)

$ErrorActionPreference = 'Stop'

if ($PSVersionTable.PSEdition -ne 'Core' -or $PSVersionTable.PSVersion -lt [version]'7.6') {
  throw 'Release builds require PowerShell 7.6 or newer through pwsh.'
}

$repoRoot = Split-Path -Parent $PSScriptRoot
$signerPolicyPath = Join-Path $repoRoot 'release\launcher-release-signer-policy.v1.json'
$signerPolicy = Get-Content -Raw -LiteralPath $signerPolicyPath | ConvertFrom-Json
if ($signerPolicy.schema -cne 'rusty.kiosk.launcher_release_signer_policy.v1' -or
    $signerPolicy.package -cne 'io.github.mesmerprism.rustykiosk.launcher' -or
    [string]$signerPolicy.signer_sha256 -notmatch '^[0-9a-f]{64}$') {
  throw 'The reviewed launcher signer policy is malformed.'
}
$releasePackages = [ordered]@{
  Store = 'io.github.mesmerprism.rustykiosk.launcher'
  LabsStore = 'io.github.mesmerprism.rustykiosk.launcher.labstore'
  Business = 'io.github.mesmerprism.rustykiosk.launcher.business'
}
if ($Distribution -cnotin @($releasePackages.Keys)) {
  throw 'Distribution must be exactly Store, LabsStore, or Business.'
}
$releasePackage = [string]$releasePackages[$Distribution]
$productionTarget = if ($Distribution -ceq 'LabsStore') {
  'io.github.mesmerprism.rustykiosk.labs'
} else {
  'io.github.mesmerprism.rustykiosk'
}
$productChannel = if ($Distribution -ceq 'LabsStore') { 'labs' } else { 'stable' }
$expectedLabel = switch ($Distribution) {
  'Store' { 'Rusty Launcher Lite' }
  'LabsStore' { 'Rusty Kiosk Lab Launcher' }
  default { 'Rusty Kiosk Launcher' }
}
$runtimeMode = if ($Distribution -ceq 'Store') { 'standalone-lite-hybrid' } else { 'trusted-handoff' }
$gradleModule = if ($Distribution -ceq 'Store') { 'launcher-lite' } else { 'launcher' }

if (
  -not [string]::IsNullOrWhiteSpace($env:RUSTY_KIOSK_LAUNCHER_DISTRIBUTION) -and
  $env:RUSTY_KIOSK_LAUNCHER_DISTRIBUTION -cne $Distribution
) {
  throw 'The ambient launcher distribution conflicts with the requested release identity.'
}
if (-not [string]::IsNullOrWhiteSpace($env:RUSTY_KIOSK_LAUNCHER_APPLICATION_ID)) {
  throw 'Release builds reject the retired arbitrary launcher application-id override.'
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

$priorDistribution = $env:RUSTY_KIOSK_LAUNCHER_DISTRIBUTION
$env:RUSTY_KIOSK_LAUNCHER_DISTRIBUTION = $Distribution
try {
  Push-Location $repoRoot
  try {
    & .\gradlew.bat --console=plain `
      ":$gradleModule`:clean" `
      ":$gradleModule`:testDebugUnitTest" `
      ":$gradleModule`:lintRelease" `
      ":$gradleModule`:assembleRelease"
    if ($LASTEXITCODE -ne 0) {
      throw "Launcher release build failed with exit code $LASTEXITCODE."
    }
  } finally {
    Pop-Location
  }
} finally {
  $env:RUSTY_KIOSK_LAUNCHER_DISTRIBUTION = $priorDistribution
}

$apkPath =
  Join-Path $repoRoot "$gradleModule\build\outputs\apk\release\$gradleModule-release.apk"
if (-not (Test-Path -LiteralPath $apkPath -PathType Leaf)) {
  throw 'The exact launcher release APK output was not produced.'
}
$apk = Get-Item -LiteralPath $apkPath

$signatureOutput = @(& $apksigner verify --verbose --print-certs $apk.FullName 2>&1)
if ($LASTEXITCODE -ne 0 -or ($signatureOutput -join "`n") -notmatch 'Verifies') {
  throw 'Launcher release APK signature verification failed.'
}
$badging = (& $aapt2 dump badging $apk.FullName) -join "`n"
$permissions = (& $aapt2 dump permissions $apk.FullName) -join "`n"
$manifest = (& $aapt2 dump xmltree --file AndroidManifest.xml $apk.FullName) -join "`n"
$archiveEntries = (& jar tf $apk.FullName) -join "`n"
$packageMatch = [regex]::Match($badging, "(?m)^package: name='([^']+)'")
$launchActivityMatch =
  [regex]::Match($badging, "(?m)^launchable-activity: name='([^']+)'")
$queriesBlock =
  [regex]::Match(
    $manifest,
    '(?ms)^\s*E: queries\b(?<body>.*?)(?=^\s*E: application\b)'
  ).Groups['body'].Value
$queryPackages = @(
  [regex]::Matches(
    $queriesBlock,
    '(?ms)^\s*E: package\b.*?Raw: "([^"]+)"'
  ) |
    ForEach-Object { $_.Groups[1].Value }
)
$expectedActivity = if ($Distribution -ceq 'Store') {
  'io.github.mesmerprism.rustykiosk.launcher.lite.RustyLauncherLiteActivity'
} else {
  'io.github.mesmerprism.rustykiosk.launcher.RustyKioskLauncherActivity'
}
$declaredPermissions = @([regex]::Matches($permissions, "(?m)^uses-permission: name='([^']+)'") | ForEach-Object { $_.Groups[1].Value })
$requiredLitePermissions = @(
  'android.permission.ACCESS_WIFI_STATE',
  'org.khronos.openxr.permission.OPENXR',
  'org.khronos.openxr.permission.OPENXR_SYSTEM'
)
$allowedLitePermissions = $requiredLitePermissions + @('com.oculus.permission.HAND_TRACKING')
$permissionPolicy = if ($Distribution -ceq 'Store') {
  @($requiredLitePermissions | Where-Object { $_ -cnotin $declaredPermissions }).Count -eq 0 -and
    @($declaredPermissions | Where-Object { $_ -cnotin $allowedLitePermissions }).Count -eq 0 -and
    @($declaredPermissions | Sort-Object -Unique).Count -eq $declaredPermissions.Count
} else {
  $declaredPermissions.Count -eq 0
}
$queryIntentCount = @([regex]::Matches($queriesBlock, '(?m)^\s*E: intent\b')).Count

# Package visibility admits four catalogue front doors plus the fixed Home navigation route.
$expectedLiteQueryCategories = @(
  'android.intent.category.LAUNCHER', 'android.intent.category.LEANBACK_LAUNCHER',
  'com.oculus.intent.category.2D', 'com.oculus.intent.category.VR', 'android.intent.category.HOME'
)
$actualLiteQueryCategories = @()
$liteQueryShapeValid = $true
foreach ($intentBlock in [regex]::Matches($queriesBlock, '(?ms)^\s*E: intent\b(?<body>.*?)(?=^\s*E: intent\b|\z)')) {
  $body = $intentBlock.Groups['body'].Value
  $actions = [regex]::Matches($body, '(?ms)E: action\b.*?Raw: "([^"]+)"')
  $categories = [regex]::Matches($body, '(?ms)E: category\b.*?Raw: "([^"]+)"')
  if ($actions.Count -ne 1 -or $actions[0].Groups[1].Value -cne 'android.intent.action.MAIN' -or $categories.Count -ne 1) {
    $liteQueryShapeValid = $false
  } else { $actualLiteQueryCategories += $categories[0].Groups[1].Value }
}
$liteQueryPolicy = $liteQueryShapeValid -and $queryIntentCount -eq 5 -and
  (Compare-Object $expectedLiteQueryCategories $actualLiteQueryCategories).Count -eq 0

$sdkComponentPolicy = $manifest -notmatch '(?m)^\s*E: (provider|receiver)'
$serviceBlocks = [regex]::Matches($manifest,
  '(?ms)^\s*E: service\b(?<body>.*?)(?=^\s*E: (?:activity|service|provider|receiver|meta-data)\b|\z)')
if ($Distribution -ceq 'Store') {
  $sdkComponentPolicy = $sdkComponentPolicy -and $serviceBlocks.Count -eq 1
  if ($serviceBlocks.Count -eq 1) {
    $body = $serviceBlocks[0].Groups['body'].Value
    $sdkComponentPolicy = $sdkComponentPolicy -and
      $body -match 'android:name[^\r\n]*Raw: "com\.meta\.spatial\.channels\.ChannelBrokerService"' -and
      $body -match 'android:exported[^\r\n]*(?:=false|\(type 0x12\)0x0)' -and
      @([regex]::Matches($body, '(?m)^\s*A:')).Count -eq 2 -and
      $body -notmatch '(?m)^\s*E:'
  }
} else {
  $sdkComponentPolicy = $sdkComponentPolicy -and $serviceBlocks.Count -eq 0
}

$checks = [ordered]@{
  package_id =
    $packageMatch.Success -and $packageMatch.Groups[1].Value -ceq $releasePackage
  application_label = $badging -match "application-label:'$([regex]::Escape($expectedLabel))'"
  version_code = $badging -match "versionCode='[1-9][0-9]*'"
  version_name = $badging -match "versionName='[0-9]+\.[0-9]+\.[0-9]+'"
  install_location_auto = $badging -match "install-location:'auto'"
  min_sdk_supported = $badging -match "minSdkVersion:'3[0-4]'"
  target_sdk_supported = $badging -match "targetSdkVersion:'3[2-6]'"
  launch_activity =
    $launchActivityMatch.Success -and
    $launchActivityMatch.Groups[1].Value -ceq $expectedActivity
  head_tracking_required = $badging -match "uses-feature: name='android\.hardware\.vr\.headtracking'"
  release_not_debuggable = $badging -notmatch 'application-debuggable'
  category_2d = $manifest -match 'com\.oculus\.intent\.category\.2D'
  category_launcher = $manifest -match 'android\.intent\.category\.LAUNCHER'
  launch_surface_policy = if ($Distribution -ceq 'Store') {
    $liteQueryPolicy -and
      $manifest -match 'RustyLauncherLiteSpatialActivity' -and
      $manifest -match 'com\.oculus\.intent\.category\.VR'
  } else {
    $manifest -notmatch 'com\.oculus\.intent\.category\.VR$'
  }
  recents_policy = if ($Distribution -ceq 'Store') {
    $manifest -notmatch 'excludeFromRecents.*=true'
  } else {
    $manifest -match 'excludeFromRecents.*=true'
  }
  supported_devices = $manifest -match 'com\.oculus\.supportedDevices'
  exact_target_query =
    $queryPackages.Count -eq 1 -and $queryPackages[0] -ceq $productionTarget
  permission_policy = $permissionPolicy
  sdk_component_policy = $sdkComponentPolicy
  native_library_policy = if ($Distribution -ceq 'Store') {
    $archiveEntries -match '(?m)^lib/arm64-v8a/' -and
      $archiveEntries -notmatch '(?m)^lib/(?!arm64-v8a/)'
  } else { $archiveEntries -notmatch '(?m)^lib/' }
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
if ($signerDigests[0] -cne [string]$signerPolicy.signer_sha256) {
  throw 'The release APK signer does not match the reviewed launcher signer policy.'
}

$metadata = [ordered]@{
  schema = 'rusty.kiosk.launcher.release_build.v2'
  created_at_utc = (Get-Date).ToUniversalTime().ToString('o')
  distribution = $Distribution
  product_channel = $productChannel
  runtime_mode = $runtimeMode
  companion_required = $Distribution -cne 'Store'
  distribution_track = if ($Distribution -ceq 'Business') { 'meta-private-app' } else { 'meta-store-app' }
  apk = [IO.Path]::GetFullPath($apk.FullName)
  sha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $apk.FullName).Hash.ToLowerInvariant()
  signer_sha256 = $signerDigests[0]
  signer_policy = [ordered]@{
    schema = [string]$signerPolicy.schema
    sha256 = (Get-FileHash -LiteralPath $signerPolicyPath -Algorithm SHA256).Hash.ToLowerInvariant()
  }
  package = $releasePackage
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
