[CmdletBinding()]
param([string]$OutputDir = 'artifacts\rusty-launcher-lite-native-preview')
$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path -LiteralPath (Split-Path -Parent $PSScriptRoot)).Path
$project = Join-Path $repoRoot 'tools\rusty-kiosk-native-panel-preview-android'
$module = Join-Path $project 'lite-panel-preview'
$output = [IO.Path]::GetFullPath((Join-Path $repoRoot $OutputDir))
if (-not $output.StartsWith($repoRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
  throw 'Preview output must remain inside the repository.'
}
$spatialSource = Get-Content (Join-Path $repoRoot 'launcher-lite\src\main\java\io\github\mesmerprism\rustykiosk\launcher\lite\RustyLauncherLiteSpatialActivity.kt') -Raw
if ($spatialSource -notmatch 'WIDTH_METERS\s*=\s*1\.3f' -or $spatialSource -notmatch 'HEIGHT_METERS\s*=\s*0\.775f' -or $spatialSource -notmatch 'dpPerMeter\s*=\s*800f') {
  throw 'Lite production immersive dimensions changed; update native fixtures to match before rendering.'
}
$inputs = @(
  Get-ChildItem (Join-Path $repoRoot 'launcher-lite\src\main\res') -Recurse -File
  Get-ChildItem (Join-Path $repoRoot 'launcher-lite\src\main\java') -Recurse -File
  Get-ChildItem (Join-Path $repoRoot 'shared\catalog-search\src\main\java') -Recurse -File
  Get-ChildItem $module -Recurse -File | Where-Object { $_.FullName -notmatch '[\\/]build[\\/]|[\\/]snapshots[\\/]' }
  Get-Item $PSCommandPath
  Get-Item (Join-Path $project 'settings.gradle.kts'), (Join-Path $project 'build.gradle.kts'), (Join-Path $project 'gradle.properties'), (Join-Path $project 'gradle\libs.versions.toml')
  Get-Item (Join-Path $repoRoot 'gradle\wrapper\gradle-wrapper.properties'), (Join-Path $repoRoot 'gradle\wrapper\gradle-wrapper.jar')
)
$sources = @($inputs | Sort-Object FullName | ForEach-Object {
  [ordered]@{ path = [IO.Path]::GetRelativePath($repoRoot, $_.FullName).Replace('\', '/'); sha256 = (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant() }
})
Push-Location $repoRoot
try {
  & .\gradlew.bat --no-daemon -p $project :lite-panel-preview:recordPaparazziDebug --tests 'io.github.mesmerprism.rustykiosk.launcher.lite.LiteNativePanelRenderTest'
  if ($LASTEXITCODE -ne 0) { throw "Lite native rendering failed: $LASTEXITCODE" }
} finally { Pop-Location }
foreach ($source in $sources) {
  if ((Get-FileHash -LiteralPath (Join-Path $repoRoot $source.path) -Algorithm SHA256).Hash.ToLowerInvariant() -ne $source.sha256) {
    throw "Preview source changed during rendering: $($source.path). Render again from stable sources."
  }
}
New-Item -ItemType Directory -Force -Path $output | Out-Null
Add-Type -AssemblyName System.Drawing
$sizes = [ordered]@{ window = @(1024, 640); immersive = @(1872, 1116); compact = @(800, 480); 'compact-scroll-end' = @(800, 480) }
$images = @(Get-ChildItem (Join-Path $module 'src\test\snapshots') -Recurse -Filter '*.png')
$artifacts = foreach ($name in $sizes.Keys) {
  $match = @($images | Where-Object { $_.BaseName.EndsWith("_$name") })
  if ($match.Count -ne 1) { throw "Expected one exact Lite snapshot for $name; found $($match.Count)." }
  $destination = Join-Path $output "$name.png"
  Copy-Item -LiteralPath $match[0].FullName -Destination $destination -Force
  $bitmap = [Drawing.Image]::FromFile($destination)
  try {
    if ($bitmap.Width -ne $sizes[$name][0] -or $bitmap.Height -ne $sizes[$name][1]) { throw "Unexpected $name raster: $($bitmap.Width)x$($bitmap.Height)" }
  } finally { $bitmap.Dispose() }
  $geometrySource = Join-Path $module "build\preview-evidence\$name.json"
  $geometryDestination = Join-Path $output "$name.geometry.json"
  Copy-Item -LiteralPath $geometrySource -Destination $geometryDestination -Force
  [ordered]@{ scenario = $name; file = "$name.png"; sha256 = (Get-FileHash $destination -Algorithm SHA256).Hash.ToLowerInvariant(); geometry = (Get-Content $geometryDestination -Raw | ConvertFrom-Json) }
}
$manifest = [ordered]@{
  schema = 'rusty.launcher.lite.native_preview.v1'
  renderer = 'android-layoutlib-paparazzi-1.3.5'
  source = 'production-activity_rusty_launcher_lite-XML-resources-and-LiteAppAdapter'
  selected_app_data = 'synthetic; preview fixture populates existing detail controls'
  authority = 'desktop native layout only; no Quest input/compositor qualification'
  window_fixture_dp = @(1024, 640)
  immersive_dp = @(1040, 620)
  immersive_dpi = 288
  compact_stress_dp = @(800, 480)
  layoutlib_runtime = '14.0.11'
  font_stream_compatibility = 'test-only exact Layoutlib runtime data/fonts disk paths; production XML/theme/font bytes unchanged'
  font_provenance_scope = 'complete resolved Layoutlib runtime data/fonts closure, including preloaded system typefaces and fonts.xml'
  fonts = @(Get-ChildItem (Join-Path $module 'build\preview-evidence\fonts') -Filter '*.sha256' | Sort-Object Name | ForEach-Object {
    [ordered]@{ file = $_.BaseName; sha256 = (Get-Content $_.FullName -Raw).Trim() }
  })
  source_git_commit = (& git -C $repoRoot rev-parse HEAD).Trim()
  source_worktree_dirty = -not [string]::IsNullOrWhiteSpace(((& git -C $repoRoot status --porcelain -- launcher-lite shared/catalog-search tools/rusty-kiosk-native-panel-preview-android tools/Export-RustyLauncherLiteNativePreview.ps1) -join "`n"))
  sources = $sources
  artifacts = @($artifacts)
}
$manifest | ConvertTo-Json -Depth 9 | Set-Content (Join-Path $output 'manifest.json') -Encoding utf8NoBOM
Get-ChildItem -LiteralPath $output | Select-Object Name, Length, FullName
