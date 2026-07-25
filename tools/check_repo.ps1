[CmdletBinding()]
param(
  [switch]$SkipAssemble
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot

if ($PSVersionTable.PSEdition -ne 'Core' -or $PSVersionTable.PSVersion -lt [version]'7.6') {
  throw 'Rusty Kiosk checks require PowerShell 7.6 or newer through pwsh.'
}

$manifestPath = Join-Path $repoRoot 'app\src\main\AndroidManifest.xml'
$serviceXmlPath = Join-Path $repoRoot 'app\src\main\res\xml\kiosk_accessibility_service.xml'
$serviceSourcePath = Join-Path $repoRoot 'app\src\main\java\io\github\mesmerprism\rustykiosk\KioskAccessibilityService.kt'
$activitySourcePath = Join-Path $repoRoot 'app\src\main\java\io\github\mesmerprism\rustykiosk\RustyKioskActivity.kt'
$passthroughSourcePath = Join-Path $repoRoot 'app\src\main\java\io\github\mesmerprism\rustykiosk\RustyKioskPassthrough.kt'
$setupBridgePath = Join-Path $repoRoot 'app\src\main\java\io\github\mesmerprism\rustykiosk\SetupHelperControlBridge.kt'
$setupManifestPath = Join-Path $repoRoot 'setup-helper\src\main\AndroidManifest.xml'
$setupSourcePath = Join-Path $repoRoot 'setup-helper\src\main\java\io\github\mesmerprism\rustykiosk\setuphelper\SetupOperations.kt'
$cliContractPath = Join-Path $repoRoot 'app\src\main\java\io\github\mesmerprism\rustykiosk\RustyKioskCliContract.kt'
$operatorProviderPath = Join-Path $repoRoot 'app\src\main\java\io\github\mesmerprism\rustykiosk\RustyKioskOperatorProvider.kt'
$cliDebugManifestPath = Join-Path $repoRoot 'app\src\debug\AndroidManifest.xml'
$cliActivityPath = Join-Path $repoRoot 'app\src\debug\java\io\github\mesmerprism\rustykiosk\RustyKioskCliActivity.kt'
$guardCliReceiverPath = Join-Path $repoRoot 'app\src\debug\java\io\github\mesmerprism\rustykiosk\RustyKioskGuardCliReceiver.kt'
$cliScriptPath = Join-Path $repoRoot 'tools\Invoke-RustyKioskCli.ps1'
$homeScriptPath = Join-Path $repoRoot 'tools\Invoke-RustyKioskHome.ps1'
$guardCliScriptPath = Join-Path $repoRoot 'tools\Invoke-RustyKioskGuardCli.ps1'
$provisionScriptPath = Join-Path $repoRoot 'tools\Provision-RustyKiosk.ps1'
$launcherManifestPath = Join-Path $repoRoot 'launcher\src\main\AndroidManifest.xml'
$launcherBuildPath = Join-Path $repoRoot 'launcher\build.gradle.kts'
$launcherTrustManifestPath =
  Join-Path $repoRoot 'launcher\trust\rusty-kiosk-v0.6.4-bundle-manifest.json'
$launcherTrustProvenancePath = Join-Path $repoRoot 'launcher\trust\README.md'
$launcherActivityPath =
  Join-Path $repoRoot 'launcher\src\main\java\io\github\mesmerprism\rustykiosk\launcher\RustyKioskLauncherActivity.java'

$serviceXml = Get-Content -Raw -LiteralPath $serviceXmlPath
if ($serviceXml -notmatch 'canRetrieveWindowContent="false"') {
  throw 'Accessibility service must keep UI-content retrieval disabled.'
}
if ($serviceXml -match 'canPerformGestures="true"') {
  throw 'Accessibility gestures are outside Rusty Kiosk scope.'
}

$serviceSource = Get-Content -Raw -LiteralPath $serviceSourcePath
$forbiddenAccessibilityTokens = @(
  'rootInActiveWindow',
  'findAccessibilityNodeInfos',
  'performAction(',
  'performGlobalAction(',
  'dispatchGesture('
)
foreach ($token in $forbiddenAccessibilityTokens) {
  if ($serviceSource.Contains($token, [StringComparison]::Ordinal)) {
    throw "Forbidden Accessibility capability found: $token"
  }
}
foreach ($token in @('disableSelf()', 'user-disabled-accessibility')) {
  if (-not $serviceSource.Contains($token, [StringComparison]::Ordinal)) {
    throw "Accessibility must keep the direct user-disable path: $token"
  }
}

$activitySource = Get-Content -Raw -LiteralPath $activitySourcePath
foreach ($token in @(
  'RustyKioskPassthroughController',
  'KioskPassthroughStyle.NATURAL',
  'KioskPassthroughStyle.CONTOUR_LUT'
)) {
  if (-not $activitySource.Contains($token, [StringComparison]::Ordinal)) {
    throw "Spatial activity is missing the passthrough control path: $token"
  }
}
if ($activitySource -match 'skybox|Composition\.glxf|collab_room') {
  throw 'The one-panel example must not restore a room or skybox.'
}
$passthroughSource = Get-Content -Raw -LiteralPath $passthroughSourcePath
foreach ($token in @(
  'scene.enablePassthrough(true)',
  'scene.setPassthroughLUT(lut)',
  'scene.isSystemPassthroughEnabled()',
  'private var activeLut: Lut?',
  'neighborhoodEdgeDetection=false'
)) {
  if (-not $passthroughSource.Contains($token, [StringComparison]::Ordinal)) {
    throw "The Spatial SDK passthrough implementation is missing: $token"
  }
}

$manifest = Get-Content -Raw -LiteralPath $manifestPath
if ($manifest -notmatch 'com\.oculus\.feature\.PASSTHROUGH') {
  throw 'The app manifest must declare the Meta passthrough capability.'
}
if ($manifest -match 'android\.app\.role\.HOME|android\.intent\.category\.HOME') {
  throw 'Rusty Kiosk must not claim the Android HOME role.'
}
if ($manifest -match 'android\.permission\.WRITE_SECURE_SETTINGS') {
  throw 'The main Rusty Kiosk APK must not receive broad secure-settings authority.'
}
if ($manifest -notmatch 'io\.github\.mesmerprism\.rustykiosk\.permission\.SETUP_CONTROL') {
  throw 'The main app is missing its same-signer setup control permission.'
}
if ($manifest -match 'com\.termux|RUN_COMMAND') {
  throw 'The product manifest must not depend on Termux.'
}
if ($manifest -match 'RustyKioskCliActivity|RustyKioskGuardCliReceiver') {
  throw 'The release/main manifest must not contain the debug CLI boundary.'
}
foreach ($pattern in @(
  'RustyKioskOperatorProvider',
  'io.github.mesmerprism.rustykiosk.operator',
  'android:permission="android.permission.DUMP"'
)) {
  if ($manifest -notmatch [Regex]::Escape($pattern)) {
    throw "The release host-operator manifest boundary is missing: $pattern"
  }
}

$cliDebugManifest = Get-Content -Raw -LiteralPath $cliDebugManifestPath
foreach ($pattern in @(
  'RustyKioskCliActivity',
  'RustyKioskGuardCliReceiver',
  'android:exported="true"',
  'android:permission="android.permission.DUMP"'
)) {
  if ($cliDebugManifest -notmatch [Regex]::Escape($pattern)) {
    throw "The debug CLI manifest boundary is missing: $pattern"
  }
}

$cliActivity = Get-Content -Raw -LiteralPath $cliActivityPath
$guardCliReceiver = Get-Content -Raw -LiteralPath $guardCliReceiverPath
$cliContract = Get-Content -Raw -LiteralPath $cliContractPath
$operatorProvider = Get-Content -Raw -LiteralPath $operatorProviderPath
$cliScript = Get-Content -Raw -LiteralPath $cliScriptPath
foreach ($token in @('RustyKioskCliProtocol.parse', 'RustyKioskCliStore(this).enqueue')) {
  if (-not $cliActivity.Contains($token, [StringComparison]::Ordinal)) {
    throw "The debug CLI admission adapter is missing: $token"
  }
}
foreach ($token in @(
  'rusty.kiosk.host_operator.v2',
  'METHOD_TAG_READ',
  'METHOD_TAG_WRITE_BEGIN',
  'METHOD_TAG_WRITE_CHUNK',
  'METHOD_TAG_WRITE_COMMIT',
  'TagFileCodec.MAX_BYTES',
  'MessageDigest.getInstance("SHA-256")',
  'TagFileStore(providerContext).replaceJson'
)) {
  if (-not $operatorProvider.Contains($token, [StringComparison]::Ordinal)) {
    throw "The bounded host tag-transfer contract is missing: $token"
  }
}
foreach ($token in @('EXTRA_VALUE_BASE64', 'Base64.decode')) {
  if (-not $cliActivity.Contains($token, [StringComparison]::Ordinal) -and
      -not $cliContract.Contains($token, [StringComparison]::Ordinal)) {
    throw "The CLI text transport is missing: $token"
  }
}
foreach ($token in @('ToBase64String', 'rusty_kiosk_cli_value_base64')) {
  if (-not $cliScript.Contains($token, [StringComparison]::Ordinal)) {
    throw "The host CLI text transport is missing: $token"
  }
}
foreach ($token in @('raw-shell', 'Runtime.getRuntime', 'ProcessBuilder', 'java.lang.Process')) {
  if ($cliActivity.Contains($token, [StringComparison]::Ordinal) -or
      $cliContract.Contains($token, [StringComparison]::Ordinal) -or
      $operatorProvider.Contains($token, [StringComparison]::Ordinal)) {
    throw "The debug CLI must not expose process or raw-shell authority: $token"
  }
}
foreach ($token in @(
  'RustyKioskCliProtocol.parse',
  'RustyKioskCliStore(providerContext).enqueue',
  'METHOD_INVOKE',
  'METHOD_RESULT',
  'RESULT_BASE64',
  'supports call() only'
)) {
  if (-not $operatorProvider.Contains($token, [StringComparison]::Ordinal)) {
    throw "The release host-operator boundary is missing: $token"
  }
}
foreach ($token in @(
  'ACTION_EXTERNAL_HOME_TRANSITION',
  'ACTION_INTERNAL_HOME_TRANSITION',
  'validRequestId'
)) {
  if (-not $guardCliReceiver.Contains($token, [StringComparison]::Ordinal) -and
      -not $serviceSource.Contains($token, [StringComparison]::Ordinal)) {
    throw "The exact guard CLI boundary is missing: $token"
  }
}
foreach ($token in @('CliValueRule', 'MAX_VALUE_LENGTH', 'MAX_RESULT_ENTRIES', 'visibleEntries')) {
  if (-not $cliContract.Contains($token, [StringComparison]::Ordinal)) {
    throw "The bounded CLI contract is missing: $token"
  }
}
if ($cliScript -notmatch '''-s'', \$Serial') {
  throw 'The Rusty Kiosk CLI wrapper must keep every ADB command serial-scoped.'
}
$homeScript = Get-Content -Raw -LiteralPath $homeScriptPath
foreach ($token in @('android.intent.action.MAIN', 'android.intent.category.HOME', '-s $Serial')) {
  if (-not $homeScript.Contains($token, [StringComparison]::Ordinal)) {
    throw "The typed Android HOME wrapper is missing: $token"
  }
}
$guardCliScript = Get-Content -Raw -LiteralPath $guardCliScriptPath
foreach ($token in @('-s $Serial', 'RustyKioskGuardCliReceiver', 'guard-last-result.json')) {
  if (-not $guardCliScript.Contains($token, [StringComparison]::Ordinal)) {
    throw "The exact guard CLI wrapper is missing: $token"
  }
}
foreach ($scriptPath in @($cliScriptPath, $homeScriptPath, $guardCliScriptPath)) {
  $scriptContent = Get-Content -Raw -LiteralPath $scriptPath
  if ($scriptContent -match 'shell\s+input|input\s+(tap|swipe|keyevent|text)') {
    throw "Wearer-equivalent CLI wrappers must not use input injection: $scriptPath"
  }
}

$setupBridge = Get-Content -Raw -LiteralPath $setupBridgePath
foreach ($token in @(
  'SetupHelperOperation',
  'REQUEST_WIFI_ADB',
  'ENABLE_WIFI_AFTER_BOOT',
  'DISABLE_WIFI_AFTER_BOOT',
  'ENABLE_ACCESSIBILITY',
  'DISABLE_ACCESSIBILITY',
  'CONTROL_PERMISSION'
)) {
  if (-not $setupBridge.Contains($token, [StringComparison]::Ordinal)) {
    throw "The fixed setup-helper bridge is missing: $token"
  }
}
foreach ($token in @('RUN_COMMAND', 'userCommand', 'rawShell', 'ProcessBuilder', 'Runtime.getRuntime')) {
  if ($setupBridge.Contains($token, [StringComparison]::Ordinal)) {
    throw "The setup-helper bridge must not expose an expandable command surface: $token"
  }
}

$setupManifest = Get-Content -Raw -LiteralPath $setupManifestPath
$setupSource = Get-Content -Raw -LiteralPath $setupSourcePath
foreach ($pattern in @(
  'android.permission.WRITE_SECURE_SETTINGS',
  'android.permission.RECEIVE_BOOT_COMPLETED',
  'android:protectionLevel="signature"',
  'android:permission="io.github.mesmerprism.rustykiosk.permission.SETUP_CONTROL"'
)) {
  if ($setupManifest -notmatch [Regex]::Escape($pattern)) {
    throw "The dedicated setup helper manifest is missing: $pattern"
  }
}
if ($setupManifest -match 'android.permission.INTERNET|android.intent.category.LAUNCHER') {
  throw 'The setup helper must remain non-networked and must not expose a launcher UI.'
}
foreach ($token in @(
  'ACCESSIBILITY_COMPONENT',
  'AccessibilityServiceList.enable',
  'AccessibilityServiceList.disable',
  'Settings.Global.ADB_ENABLED',
  'WIFI_ADB_SETTING',
  'ACTION_BOOT_COMPLETED'
)) {
  if (-not $setupSource.Contains($token, [StringComparison]::Ordinal)) {
    throw "The fixed setup helper is missing: $token"
  }
}
foreach ($token in @('ProcessBuilder', 'Runtime.getRuntime', 'java.lang.Process', 'startActivity(')) {
  if ($setupSource.Contains($token, [StringComparison]::Ordinal)) {
    throw "The setup helper contains forbidden generic authority: $token"
  }
}

$provisionScript = Get-Content -Raw -LiteralPath $provisionScriptPath
foreach ($token in @('-s $Serial', 'WRITE_SECURE_SETTINGS', 'setup-helper-debug.apk', 'dumpsys package')) {
  if (-not $provisionScript.Contains($token, [StringComparison]::Ordinal)) {
    throw "The serial-scoped provisioning workflow is missing: $token"
  }
}
if ($provisionScript -match 'shell\s+input|input\s+(tap|swipe|keyevent|text)') {
  throw 'The provisioning workflow must not use input injection.'
}

$launcherManifest = Get-Content -Raw -LiteralPath $launcherManifestPath
$launcherBuild = Get-Content -Raw -LiteralPath $launcherBuildPath
$launcherTrustManifest =
  Get-Content -Raw -LiteralPath $launcherTrustManifestPath | ConvertFrom-Json
$launcherTrustProvenance = Get-Content -Raw -LiteralPath $launcherTrustProvenancePath
$launcherActivity = Get-Content -Raw -LiteralPath $launcherActivityPath
foreach ($token in @(
  'io.github.mesmerprism.rustykiosk',
  'com.oculus.intent.category.2D',
  'android.intent.category.LAUNCHER',
  'android.hardware.vr.headtracking',
  'com.oculus.supportedDevices',
  'android:excludeFromRecents="true"'
)) {
  if (-not $launcherManifest.Contains($token, [StringComparison]::Ordinal)) {
    throw "The native 2D launcher manifest is missing: $token"
  }
}
foreach ($token in @(
  'uses-permission',
  'QUERY_ALL_PACKAGES',
  '<service',
  '<provider',
  '<receiver',
  'com.oculus.intent.category.VR'
)) {
  if ($launcherManifest.Contains($token, [StringComparison]::Ordinal)) {
    throw "The native 2D launcher contains forbidden authority: $token"
  }
}
if (@([regex]::Matches($launcherManifest, '<package\s+android:name=')).Count -ne 1) {
  throw 'The native 2D launcher must query exactly one package.'
}
foreach ($token in @(
  'io.github.mesmerprism.rustykiosk.launcher',
  'rusty-kiosk-v0.6.4-bundle-manifest.json',
  'expectedTargetSignerSha256'
)) {
  if (-not $launcherBuild.Contains($token, [StringComparison]::Ordinal)) {
    throw "The native 2D launcher build identity is missing: $token"
  }
}
$trustedKioskApk = @(
  $launcherTrustManifest.files |
    Where-Object { $_.name -eq 'rusty-kiosk.apk' }
)
if (
  $launcherTrustManifest.schema -cne 'meta.quest.file_manager.rusty_kiosk_bundle.v1' -or
  $launcherTrustManifest.build_type -cne 'release' -or
  $launcherTrustManifest.version -cne '0.6.4' -or
  $launcherTrustManifest.source_url -cne 'https://github.com/MesmerPrism/Rusty-Kiosk' -or
  $launcherTrustManifest.source_revision -cne 'c00bfdb386850692ec977b7f3a22fb187ccc9450' -or
  $launcherTrustManifest.signer_sha256 -cne
    '423d20004c79dd140c692e31aa80369cd3677b1ae2688dbd75011a4c83a0f1fb' -or
  $trustedKioskApk.Count -ne 1 -or
  $trustedKioskApk[0].sha256 -cne
    '07306cc03d961fe046e6b5c822b4f28e89386ca62f0930fbe58733f0b7f2600e'
) {
  throw 'The native 2D launcher trust anchor no longer matches Rusty Kiosk v0.6.4.'
}
foreach ($token in @(
  'https://github.com/MesmerPrism/Rusty-Kiosk/releases/tag/v0.6.4',
  'https://github.com/MesmerPrism/Rusty-Kiosk/releases/download/v0.6.4/bundle-manifest.json',
  'e0fe76729adb13c247a45f9f45e5990ce6610a2859818dfd135a2b8304715fc2'
)) {
  if (-not $launcherTrustProvenance.Contains($token, [StringComparison]::Ordinal)) {
    throw "The native 2D launcher trust provenance is missing: $token"
  }
}
foreach ($token in @(
  'com.meta.spatial',
  'REQUEST_INSTALL_PACKAGES',
  'QUERY_ALL_PACKAGES'
)) {
  if ($launcherBuild.Contains($token, [StringComparison]::Ordinal)) {
    throw "The native 2D launcher build contains forbidden authority: $token"
  }
}
foreach ($token in @(
  'getPackageInfo(',
  'PackageManager.GET_SIGNING_CERTIFICATES',
  'getLaunchIntentForPackage(',
  'finishAndRemoveTask()',
  'https://mesmerprism.com/Meta-Quest-File-Manager/#kiosk',
  'https://github.com/MesmerPrism/Rusty-Kiosk/releases/latest'
)) {
  if (-not $launcherActivity.Contains($token, [StringComparison]::Ordinal)) {
    throw "The native 2D launcher trust/handoff path is missing: $token"
  }
}
foreach ($token in @(
  'PackageInstaller',
  'AccessibilityService',
  'startService(',
  'bindService(',
  'Runtime.getRuntime',
  'ProcessBuilder'
)) {
  if ($launcherActivity.Contains($token, [StringComparison]::Ordinal)) {
    throw "The native 2D launcher Activity contains forbidden authority: $token"
  }
}

$publicFiles =
  Get-ChildItem -LiteralPath $repoRoot -Recurse -File |
    Where-Object {
      $_.FullName -notmatch '[\\/](\.git|\.gradle|\.kotlin|build|local-artifacts|artifacts)[\\/]'
    }
$privatePatterns = @(
  [Regex]::Escape(('S:' + '\Work' + '\')),
  [Regex]::Escape(('C:' + '\Users' + '\')),
  ('Viscere' + 'ality'),
  ('Study ' + '6'),
  ('34' + '87'),
  ('340' + 'Y')
)
foreach ($file in $publicFiles) {
  if ($file.Extension -in @('.jar', '.png', '.jpg', '.jpeg', '.gif', '.webp')) {
    continue
  }
  $content = Get-Content -Raw -LiteralPath $file.FullName -ErrorAction SilentlyContinue
  foreach ($pattern in $privatePatterns) {
    if ($content -match $pattern) {
      throw "Public-boundary pattern '$pattern' found in $($file.FullName)."
    }
  }
}

Push-Location $repoRoot
try {
  & pwsh -NoProfile -ExecutionPolicy Bypass `
    -File .\tools\Test-RustyKioskPanelPreview.ps1
  if ($LASTEXITCODE -ne 0) {
    throw "Panel preview contract gate failed with exit code $LASTEXITCODE."
  }
  & .\gradlew.bat testDebugUnitTest lintDebug
  if ($LASTEXITCODE -ne 0) {
    throw "Gradle unit/lint gate failed with exit code $LASTEXITCODE."
  }
  & .\gradlew.bat `
    :app:processReleaseMainManifest `
    :launcher:processReleaseMainManifest `
    :setup-helper:processReleaseMainManifest
  if ($LASTEXITCODE -ne 0) {
    throw "Gradle release-manifest gate failed with exit code $LASTEXITCODE."
  }
  $releaseManifest =
    Get-ChildItem -Path .\app\build\intermediates -Recurse -Filter AndroidManifest.xml |
      Where-Object { $_.FullName -match '[\\/]release[\\/]' } |
      Select-Object -First 1
  if ($null -eq $releaseManifest) {
    throw 'The release-manifest gate did not produce a merged manifest.'
  }
  if ((Get-Content -Raw -LiteralPath $releaseManifest.FullName) -match
      'RustyKioskCliActivity|RustyKioskGuardCliReceiver') {
    throw 'A debug Rusty Kiosk CLI component leaked into the release manifest.'
  }
  $launcherReleaseManifest =
    Get-ChildItem -Path .\launcher\build\intermediates -Recurse -Filter AndroidManifest.xml |
      Where-Object { $_.FullName -match '[\\/]release[\\/]' } |
      Select-Object -First 1
  if ($null -eq $launcherReleaseManifest) {
    throw 'The launcher release-manifest gate did not produce a merged manifest.'
  }
  $launcherReleaseText = Get-Content -Raw -LiteralPath $launcherReleaseManifest.FullName
  if ($launcherReleaseText -match
      '<uses-permission|<service|<provider|<receiver|QUERY_ALL_PACKAGES|com\.oculus\.intent\.category\.VR"') {
    throw 'Forbidden authority leaked into the native 2D launcher release manifest.'
  }
  if (-not $SkipAssemble) {
    & .\gradlew.bat :app:assembleDebug :launcher:assembleDebug :setup-helper:assembleDebug
    if ($LASTEXITCODE -ne 0) {
      throw "Gradle debug assembly failed with exit code $LASTEXITCODE."
    }
  }
  git diff --check
  if ($LASTEXITCODE -ne 0) {
    throw 'git diff --check failed.'
  }
} finally {
  Pop-Location
}

Write-Output 'Rusty Kiosk repository checks passed.'
