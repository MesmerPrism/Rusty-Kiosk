[CmdletBinding()]
param(
  [string]$OutputDir = 'artifacts\rusty-kiosk-native-panel-preview'
)

$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path -LiteralPath (Split-Path -Parent $PSScriptRoot)).Path
$previewProject = Join-Path $repoRoot 'tools\rusty-kiosk-native-panel-preview-android'
$outputPath = [IO.Path]::GetFullPath((Join-Path $repoRoot $OutputDir))
$snapshotPath =
  Join-Path $previewProject 'native-panel-preview\src\test\snapshots'
$expectedNames = @('catalog-ready', 'tag-filter-missing', 'guard-setup')

if (-not $outputPath.StartsWith($repoRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
  throw 'Native preview output must remain inside the repository.'
}

Push-Location $repoRoot
try {
  & .\gradlew.bat --no-daemon -p $previewProject `
    :native-panel-preview:recordPaparazziDebug `
    --tests 'io.github.mesmerprism.rustykiosk.RustyKioskNativePanelRenderTest'
  if ($LASTEXITCODE -ne 0) {
    throw "Native panel Paparazzi render failed with exit code $LASTEXITCODE."
  }
} finally {
  Pop-Location
}

New-Item -ItemType Directory -Force -Path $outputPath | Out-Null
$images = @(Get-ChildItem -LiteralPath $snapshotPath -Recurse -Filter '*.png')
foreach ($name in $expectedNames) {
  $match = $images | Where-Object { $_.BaseName -like "*$name*" } | Select-Object -First 1
  if (-not $match) { throw "Missing native Android panel render: $name" }
  Copy-Item -LiteralPath $match.FullName -Destination (Join-Path $outputPath "$name.png") -Force
}

Add-Type -AssemblyName System.Drawing
$artifacts = foreach ($name in $expectedNames) {
  $file = Join-Path $outputPath "$name.png"
  $bitmap = [Drawing.Image]::FromFile($file)
  try {
    if ($bitmap.Width -ne 1953 -or $bitmap.Height -ne 1323) {
      throw "Native panel render $name has unexpected dimensions $($bitmap.Width)x$($bitmap.Height); expected 1953x1323."
    }
  } finally {
    $bitmap.Dispose()
  }
  [ordered]@{
    scenario = $name
    file = "$name.png"
    bytes = (Get-Item -LiteralPath $file).Length
    sha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $file).Hash.ToLowerInvariant()
  }
}

$sourceFiles = @(
  'app/src/main/java/io/github/mesmerprism/rustykiosk/CatalogModels.kt',
  'app/src/main/java/io/github/mesmerprism/rustykiosk/RustyKioskPanel.kt',
  'app/src/main/java/io/github/mesmerprism/rustykiosk/RustyKioskPanelContract.kt',
  'app/src/main/java/io/github/mesmerprism/rustykiosk/RustyKioskTheme.kt'
)
$sourceStatus = (& git -C $repoRoot status --porcelain -- $sourceFiles) -join "`n"
$sources = foreach ($relativePath in $sourceFiles) {
  $sourcePath = Join-Path $repoRoot $relativePath
  [ordered]@{
    path = $relativePath
    sha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $sourcePath).Hash.ToLowerInvariant()
  }
}
$manifest = [ordered]@{
  schema = 'rusty.kiosk.native_panel_preview_manifest.v1'
  renderer = 'android-layoutlib-paparazzi-1.3.5'
  source = 'production-RustyKioskPanel-Compose'
  logical_width_dp = 1085
  logical_height_dp = 735
  native_raster_width_px = 1953
  native_raster_height_px = 1323
  spatial_layout_dpi = 288
  source_git_commit = (& git -C $repoRoot rev-parse HEAD).Trim()
  source_worktree_dirty = -not [string]::IsNullOrWhiteSpace($sourceStatus)
  sources = @($sources)
  artifacts = @($artifacts)
}
$manifest |
  ConvertTo-Json -Depth 6 |
  Set-Content -LiteralPath (Join-Path $outputPath 'manifest.json') -Encoding utf8NoBOM

Get-ChildItem -LiteralPath $outputPath | Sort-Object Name | Select-Object Name, Length, FullName
