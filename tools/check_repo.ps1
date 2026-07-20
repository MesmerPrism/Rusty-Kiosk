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

$activitySource = Get-Content -Raw -LiteralPath $activitySourcePath
if ($activitySource -notmatch 'scene\.enablePassthrough\(true\)') {
  throw 'Spatial activity must enable system passthrough.'
}
if ($activitySource -match 'skybox|Composition\.glxf|collab_room') {
  throw 'The one-panel example must not restore a room or skybox.'
}

$manifest = Get-Content -Raw -LiteralPath $manifestPath
if ($manifest -match 'android\.app\.role\.HOME|android\.intent\.category\.HOME') {
  throw 'Rusty Kiosk must not claim the Android HOME role.'
}

$publicFiles =
  Get-ChildItem -LiteralPath $repoRoot -Recurse -File |
    Where-Object {
      $_.FullName -notmatch '[\\/](\.git|\.gradle|build|local-artifacts|artifacts)[\\/]'
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
  if (-not $SkipAssemble) {
    & .\gradlew.bat assembleDebug
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
