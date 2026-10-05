[CmdletBinding()]
param(
  [switch]$RenderNative
)

$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path -LiteralPath (Split-Path -Parent $PSScriptRoot)).Path
$contractPath = Join-Path $repoRoot 'references\rusty-kiosk-panel-contract.v1.json'
$browserRoot = Join-Path $repoRoot 'tools\rusty-kiosk-panel-browser-preview'
$panelPath =
  Join-Path $repoRoot 'app\src\main\java\io\github\mesmerprism\rustykiosk\RustyKioskPanel.kt'
$productionPanelPath =
  Join-Path $repoRoot 'app\src\main\java\io\github\mesmerprism\rustykiosk\RustyKioskNativePanel.kt'
$geometryPath =
  Join-Path $repoRoot 'app\src\main\java\io\github\mesmerprism\rustykiosk\RustyKioskPanelContract.kt'
$activityPath =
  Join-Path $repoRoot 'app\src\main\java\io\github\mesmerprism\rustykiosk\RustyKioskActivity.kt'
$manifestPath = Join-Path $repoRoot 'app\src\main\AndroidManifest.xml'
$nativeTestPath =
  Join-Path $repoRoot 'tools\rusty-kiosk-native-panel-preview-android\native-panel-preview\src\test\kotlin\io\github\mesmerprism\rustykiosk\RustyKioskNativePanelRenderTest.kt'
$nativeBuildPath =
  Join-Path $repoRoot 'tools\rusty-kiosk-native-panel-preview-android\native-panel-preview\build.gradle.kts'
$nativeExportPath = Join-Path $repoRoot 'tools\Export-RustyKioskNativePanelPreview.ps1'

$contract = Get-Content -Raw -LiteralPath $contractPath | ConvertFrom-Json
if ($contract.schema -ne 'rusty.kiosk.panel_contract.v1') {
  throw 'Unexpected Rusty Kiosk panel contract schema.'
}
if (
  $contract.surface.width_m -ne 1.55 -or
  $contract.surface.height_m -ne 1.05 -or
  $contract.surface.dp_per_meter -ne 700
) {
  throw 'Spatial panel physical/dp-per-meter contract changed.'
}
if (
  $contract.surface.width_dp -ne 1085 -or
  $contract.surface.height_dp -ne 735 -or
  $contract.surface.layout_dpi -ne 288 -or
  $contract.surface.native_raster_width_px -ne 1953 -or
  $contract.surface.native_raster_height_px -ne 1323
) {
  throw 'Spatial panel logical/native raster contract changed.'
}
if ($contract.preview.synthetic_only -ne $true) {
  throw 'Browser and native panel fixtures must remain synthetic-only.'
}
if (
  $contract.text_input.backend -ne 'android-edit-text-layoutxml-panel' -or
  $contract.text_input.panel_registration -ne 'native-layout-xml' -or
  $contract.text_input.keyboard_request -ne 'explicit-on-focus-and-click-display-scoped' -or
  $contract.text_input.content_logged -ne $false
) {
  throw 'Quest text-input authority changed.'
}

$geometry = Get-Content -Raw -LiteralPath $geometryPath
$activity = Get-Content -Raw -LiteralPath $activityPath
$manifest = Get-Content -Raw -LiteralPath $manifestPath
$panel = Get-Content -Raw -LiteralPath $panelPath
$productionPanel = Get-Content -Raw -LiteralPath $productionPanelPath
foreach ($token in @(
  'const val WIDTH_METERS = 1.55f',
  'const val HEIGHT_METERS = 1.05f',
  'const val DP_PER_METER = 700.0f',
  'const val WIDTH_DP = 1085',
  'const val HEIGHT_DP = 735',
  'const val LAYOUT_DPI = 288',
  'const val RASTER_WIDTH_PX = 1953',
  'const val RASTER_HEIGHT_PX = 1323'
)) {
  if (-not $geometry.Contains($token)) { throw "Production panel geometry is missing: $token" }
}
foreach ($token in @(
  'RustyKioskPanelGeometry.WIDTH_METERS',
  'RustyKioskPanelGeometry.HEIGHT_METERS'
)) {
  if (-not $activity.Contains($token)) { throw "Spatial host is missing shared panel geometry: $token" }
}
foreach ($token in @(
  'LayoutXMLPanelRegistration(',
  'R.layout.rusty_kiosk_panel',
  'RustyKioskPanelGeometry.WIDTH_DP',
  'RustyKioskPanelGeometry.HEIGHT_DP',
  'RustyKioskPanelGeometry.LAYOUT_DPI'
)) {
  if (-not $productionPanel.Contains($token)) {
    throw "Production native panel is missing shared registration geometry: $token"
  }
}

$html = Get-Content -Raw -LiteralPath (Join-Path $browserRoot 'index.html')
$css = Get-Content -Raw -LiteralPath (Join-Path $browserRoot 'styles.css')
$script = Get-Content -Raw -LiteralPath (Join-Path $browserRoot 'panel-preview.mjs')
foreach ($controlId in $contract.control_ids) {
  $control = [string]$controlId
  if (-not $geometry.Contains($control)) { throw "Production control contract is missing: $control" }
  if (-not $html.Contains($control) -and -not $script.Contains($control)) {
    throw "Browser projection is missing control: $control"
  }
}
foreach ($controlToken in @(
  'RustyKioskPanelControls.ROOT',
  'RustyKioskPanelControls.SEARCH',
  'RustyKioskPanelControls.TAG_FILTERS',
  'RustyKioskPanelControls.APP_LIST',
  'RustyKioskPanelControls.APP_DETAILS',
  'RustyKioskPanelControls.LAUNCH_OPTIONS',
  'RustyKioskPanelControls.LAUNCH_OPTION_LAUNCH',
  'RustyKioskPanelControls.NORMAL_LAUNCH',
  'RustyKioskPanelControls.KIOSK_LAUNCH',
  'RustyKioskPanelControls.USER_CONTROL_STATUS',
  'RustyKioskPanelControls.USER_CONTROLS_OPEN',
  'RustyKioskPanelControls.USER_CONTROLS',
  'RustyKioskPanelControls.PASSTHROUGH_CONTROLS',
  'RustyKioskPanelControls.WIFI_ADB_CONTROLS',
  'RustyKioskPanelControls.ACCESSIBILITY_TOGGLE',
  'RustyKioskPanelControls.META_HOME_EXIT'
)) {
  if (-not $productionPanel.Contains($controlToken)) {
    throw "Production native panel is missing stable control tag: $controlToken"
  }
}
foreach ($inputToken in @(
  'EditText(context).apply',
  'isFocusableInTouchMode = true',
  'showSoftInputOnFocus = true',
  'setOnFocusChangeListener',
  'setOnClickListener',
  'field.context.createDisplayContext(fieldDisplay)',
  'getSystemService(InputMethodManager::class.java)',
  'inputMethodManager.restartInput(field)',
  'inputMethodManager.showSoftInput(field, InputMethodManager.SHOW_IMPLICIT)',
  'fieldDisplayId=',
  'imeContextDisplayId=',
  'MAX_KEYBOARD_REQUEST_ATTEMPTS = 2',
  'textLogged=false'
)) {
  if (-not $productionPanel.Contains($inputToken)) {
    throw "Production native Quest IME path is missing: $inputToken"
  }
}
if (-not $activity.Contains('window.addFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)')) {
  throw 'Spatial activity is missing Meta keyboard compositor compatibility flag.'
}
if (-not $productionPanel.Contains('input = PanelInputOptions()')) {
  throw 'Spatial panel is missing explicit input configuration.'
}
foreach ($feature in @(
  'com.oculus.feature.VIRTUAL_KEYBOARD',
  'oculus.software.overlay_keyboard'
)) {
  if (-not $manifest.Contains($feature)) {
    throw "Quest manifest is missing keyboard capability: $feature"
  }
}
foreach ($token in @(
  '--panel-width: 1085px',
  '--panel-height: 735px',
  'grid-template-columns: minmax(0, 46fr) minmax(0, 54fr)',
  '--primary: #e28b45',
  '#native-panel-layer'
)) {
  if (-not $css.Contains($token)) { throw "Browser projection is missing layout token: $token" }
}
foreach ($token in @(
  'Aligned 50% comparison',
  'Browser state changed',
  'state = scenarioState(state.scenario)'
)) {
  if (-not $script.Contains($token)) { throw "Browser comparison gate is missing token: $token" }
}
foreach ($token in @(
  'rusty.kiosk.panel_contract.v1',
  'updateRendererView',
  'native_raster_width_px',
  'importPreviewState',
  'addTag',
  'removeTag',
  'renderUserControls',
  'Exit to Meta Home'
)) {
  if (-not $script.Contains($token)) { throw "Browser projection is missing behavior: $token" }
}
if ($html -match '(?i)<webview' -or $script -match '(?i)android\.webkit\.WebView') {
  throw 'The browser preview must not become a runtime WebView.'
}

$nativeTest = Get-Content -Raw -LiteralPath $nativeTestPath
$nativeBuild = Get-Content -Raw -LiteralPath $nativeBuildPath
$nativeExport = Get-Content -Raw -LiteralPath $nativeExportPath
foreach ($token in @(
  'RustyKioskPanel(',
  'RustyKioskTheme',
  'RustyKioskPanelGeometry.RASTER_WIDTH_PX',
  'Density.create(RustyKioskPanelGeometry.LAYOUT_DPI)',
  'catalog-ready',
  'tag-filter-missing',
  'guard-setup'
)) {
  if (-not $nativeTest.Contains($token)) { throw "Native Compose renderer is missing: $token" }
}
foreach ($token in @(
  'RustyKioskPanel.kt',
  'RustyKioskPanelContract.kt',
  'RustyKioskTheme.kt',
  'libs.meta.spatial.sdk.uiset',
  'alias(libs.plugins.paparazzi)'
)) {
  if (-not $nativeBuild.Contains($token)) { throw "Native host is not source-bound: $token" }
}
foreach ($token in @(
  'recordPaparazziDebug',
  'expected 1953x1323',
  "renderer = 'android-layoutlib-paparazzi-1.3.5'",
  'source_worktree_dirty'
)) {
  if (-not $nativeExport.Contains($token)) { throw "Native exporter is missing: $token" }
}

$node = Get-Command node -ErrorAction Stop
& $node.Source (Join-Path $browserRoot 'panel-model.test.mjs')
if ($LASTEXITCODE -ne 0) { throw 'Browser panel model tests failed.' }

if ($RenderNative) {
  & (Join-Path $repoRoot 'tools\Export-RustyKioskNativePanelPreview.ps1')
  if ($LASTEXITCODE -ne 0) { throw 'Native panel rendering failed.' }
}

Write-Output 'Rusty Kiosk production native panel, browser projection, and native preview contracts passed.'
