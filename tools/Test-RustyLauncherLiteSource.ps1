[CmdletBinding()]
param([string]$MergedManifestPath)
$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
$moduleRoot = Join-Path $repoRoot 'launcher-lite'
$androidNamespace = 'http://schemas.android.com/apk/res/android'
if ([string]::IsNullOrWhiteSpace($MergedManifestPath)) {
  $MergedManifestPath = Join-Path $moduleRoot 'src/main/AndroidManifest.xml'
}
[xml]$manifest = Get-Content -Raw -LiteralPath $MergedManifestPath
if ($manifest.manifest.package -and $manifest.manifest.package -cne 'io.github.mesmerprism.rustykiosk.launcher') {
  throw 'Lite merged manifest changed its published package.'
}
$build = Get-Content -Raw -LiteralPath (Join-Path $moduleRoot 'build.gradle.kts')
$signerPolicy = Get-Content -Raw -LiteralPath (Join-Path $repoRoot 'release/launcher-release-signer-policy.v1.json') | ConvertFrom-Json
if ($signerPolicy.schema -cne 'rusty.kiosk.launcher_release_signer_policy.v1' -or
    $signerPolicy.package -cne 'io.github.mesmerprism.rustykiosk.launcher' -or
    $signerPolicy.signer_sha256 -cne '23bb7bb81143a81f216118af35960aaee2468e9880b94e07574cac0a9239dcf6') {
  throw 'Launcher Lite must preserve the published package and reviewed signer.'
}
$requiredPermissions = @('android.permission.ACCESS_WIFI_STATE',
  'org.khronos.openxr.permission.OPENXR', 'org.khronos.openxr.permission.OPENXR_SYSTEM')
$allowedPermissions = $requiredPermissions + @('com.oculus.permission.HAND_TRACKING')
$graphicsFeatures = @($manifest.manifest.SelectNodes("*[local-name()='uses-feature']") |
  Where-Object { $_.HasAttribute('glEsVersion', $androidNamespace) })
if ($graphicsFeatures.Count -ne 1 -or
    $graphicsFeatures[0].GetAttribute('glEsVersion', $androidNamespace) -cne '0x00030001') {
  throw 'Lite must declare the Spatial SDK hybrid OpenGL ES 3.1 requirement exactly once.'
}
$permissions = @($manifest.manifest.SelectNodes("*[local-name()='uses-permission']") | ForEach-Object { $_.GetAttribute('name', $androidNamespace) })
if (@($requiredPermissions | Where-Object { $_ -cnotin $permissions }).Count -ne 0 -or
    @($permissions | Where-Object { $_ -cnotin $allowedPermissions }).Count -ne 0 -or
    @($permissions | Sort-Object -Unique).Count -ne $permissions.Count) {
  throw 'Lite permissions must be read-only Wi-Fi and the closed SDK set only.'
}
$application = $manifest.manifest.SelectSingleNode("*[local-name()='application']")
if (@($application.SelectNodes("*[local-name()='provider' or local-name()='receiver']")).Count -ne 0) {
  throw 'Lite may not acquire providers or receivers.'
}
$services = @($application.SelectNodes("*[local-name()='service']"))
if ($services.Count -gt 1) { throw 'Lite may include only the exact SDK channel service.' }
foreach ($service in $services) {
  $attributes = @($service.Attributes | ForEach-Object { $_.LocalName })
  if ($service.GetAttribute('name', $androidNamespace) -cne 'com.meta.spatial.channels.ChannelBrokerService' -or
      $service.GetAttribute('exported', $androidNamespace) -cne 'false' -or
      $attributes.Count -ne 2 -or (Compare-Object @('name', 'exported') $attributes).Count -ne 0 -or
      @($service.SelectNodes('*')).Count -ne 0) {
    throw 'Only the unchanged non-exported SDK ChannelBrokerService is admitted, without filters or authority expansion.'
  }
}
$activities = @($application.SelectNodes("*[local-name()='activity']"))
$expectedActivities = @('.RustyLauncherLiteActivity', '.RustyLauncherLiteSpatialActivity')
$actualActivities = @($activities | ForEach-Object { '.' + ($_.GetAttribute('name', $androidNamespace).Split('.')[-1]) })
if ($activities.Count -ne 2 -or (Compare-Object $expectedActivities $actualActivities).Count -ne 0) {
  throw 'Lite must retain exactly the closed window and immersive hosts.'
}
foreach ($activity in $activities) {
  $categories = @($activity.SelectNodes("*[local-name()='intent-filter']/*[local-name()='category']") | ForEach-Object { $_.GetAttribute('name', $androidNamespace) })
  if ('android.intent.category.HOME' -cin $categories) { throw 'Lite is not an Android HOME replacement.' }
}
$query = $manifest.manifest.SelectSingleNode("*[local-name()='queries']")
$packages = @($query.SelectNodes("*[local-name()='package']"))
$intents = @($query.SelectNodes("*[local-name()='intent']"))
if ($packages.Count -ne 1 -or $packages[0].GetAttribute('name', $androidNamespace) -cne 'io.github.mesmerprism.rustykiosk' -or $intents.Count -ne 5) {
  throw 'Lite discovery must retain one optional package, four front-door queries and one MAIN/HOME query.'
}
$expectedCategories = @('android.intent.category.LAUNCHER', 'android.intent.category.LEANBACK_LAUNCHER',
  'com.oculus.intent.category.2D', 'com.oculus.intent.category.VR', 'android.intent.category.HOME')
$actualCategories = @()
foreach ($intent in $intents) {
  $actions = @($intent.SelectNodes("*[local-name()='action']"))
  $categories = @($intent.SelectNodes("*[local-name()='category']"))
  if ($actions.Count -ne 1 -or $actions[0].GetAttribute('name', $androidNamespace) -cne 'android.intent.action.MAIN' -or $categories.Count -ne 1) {
    throw 'Lite discovery admits MAIN plus one closed category only.'
  }
  $actualCategories += $categories[0].GetAttribute('name', $androidNamespace)
}
if ((Compare-Object $expectedCategories $actualCategories).Count -ne 0) { throw 'Lite query categories drifted.' }
foreach ($token in @('applicationId = "io.github.mesmerprism.rustykiosk.launcher"', 'versionCode = 3',
  'versionName = "0.3.0"', 'isDebuggable = false', 'libs.meta.spatial')) {
  if (-not $build.Contains($token, [StringComparison]::Ordinal)) { throw "Lite build identity/SDK missing: $token" }
}
foreach ($token in @('productFlavors', 'RUSTY_KIOSK_LAUNCHER_APPLICATION_ID', 'RUSTY_KIOSK_LAUNCHER_TARGET_PACKAGE')) {
  if ($build.Contains($token, [StringComparison]::Ordinal)) { throw "Lite identity cannot expand: $token" }
}
$sourceFiles = @(Get-ChildItem -LiteralPath (Join-Path $moduleRoot 'src/main/java') -Recurse -File | Where-Object { $_.Extension -in @('.java', '.kt') })
$source = ($sourceFiles | ForEach-Object { Get-Content -Raw -LiteralPath $_.FullName }) -join "`n"
foreach ($token in @('Runtime.getRuntime', 'ProcessBuilder', 'PackageInstaller', 'AccessibilityService',
  'setWifiEnabled', 'Settings.Global.put', 'Settings.Secure.put', 'java.net.Socket', 'ServerSocket')) {
  if ($source.Contains($token, [StringComparison]::Ordinal)) { throw "Lite contains forbidden effect: $token" }
}
foreach ($token in @('LitePanelController', 'LitePreferenceStore', 'LiteCatalog.load', 'AppSystemActivity', 'LayoutXMLPanelRegistration')) {
  if (-not $source.Contains($token, [StringComparison]::Ordinal)) { throw "Shared Lite presentation/core path missing: $token" }
}
$catalogPolicy = Get-Content -Raw -LiteralPath (Join-Path $moduleRoot 'src/main/java/io/github/mesmerprism/rustykiosk/launcher/lite/LiteCatalogPolicy.java')
foreach ($token in @('!activityExported', '!activityEnabled', '!applicationEnabled', 'ownPackage.equals(packageName)', 'unique.putIfAbsent')) {
  if (-not $catalogPolicy.Contains($token, [StringComparison]::Ordinal)) { throw "Lite catalogue admission missing: $token" }
}
$builder = Get-Content -Raw -LiteralPath (Join-Path $repoRoot 'tools/Build-RustyKioskLauncherRelease.ps1')
foreach ($token in @("'launcher-lite'", "'standalone-lite-hybrid'", 'does not match the reviewed launcher signer policy')) {
  if (-not $builder.Contains($token, [StringComparison]::Ordinal)) { throw "Lite release route missing: $token" }
}
Write-Output 'Rusty Launcher Lite hybrid source checks passed.'
