[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$sourceRoot = Join-Path $repoRoot 'app\src\main\java\io\github\mesmerprism\rustykiosk'
$optionsPath = Join-Path $sourceRoot 'AppLaunchOptions.kt'
$activityPath = Join-Path $sourceRoot 'RustyKioskActivity.kt'
$launchPath = Join-Path $sourceRoot 'LaunchController.kt'
$cliPath = Join-Path $sourceRoot 'RustyKioskCliContract.kt'
$previewPath = Join-Path $repoRoot 'tools\rusty-kiosk-panel-browser-preview\panel-preview.mjs'

foreach ($path in @($optionsPath, $activityPath, $launchPath, $cliPath, $previewPath)) {
  if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
    throw "Launch-options boundary source is missing: $path"
  }
}

$options = Get-Content -Raw -LiteralPath $optionsPath
$activity = Get-Content -Raw -LiteralPath $activityPath
$launch = Get-Content -Raw -LiteralPath $launchPath
$cli = Get-Content -Raw -LiteralPath $cliPath
$preview = Get-Content -Raw -LiteralPath $previewPath

function Assert-ContainsAll {
  param([string]$Text, [string[]]$Tokens, [string]$Surface)
  foreach ($token in $Tokens) {
    if (-not $Text.Contains($token, [StringComparison]::Ordinal)) {
      throw "$Surface is missing required launch-options boundary token: $token"
    }
  }
}

Assert-ContainsAll $options @(
  'const val SCHEMA = "rusty.quest.app_launch_options.v1"',
  'const val MAX_OPTION_COUNT = 64',
  'const val MAX_OPTION_ID_LENGTH = 160',
  'providerAuthority == packageName + AppLaunchOptionsContract.PROVIDER_AUTHORITY_SUFFIX',
  'ownerActivity == launchActivity',
  'packageManager.resolveContentProvider(authority, 0)',
  'provider.enabled && provider.exported && !provider.grantUriPermissions',
  'packageManager.getPackagesForUid(applicationInfo.uid)?.toList() == listOf(packageName)',
  'activity.enabled && activity.exported && activity.applicationInfo.uid == applicationInfo.uid',
  'identity.uid == applicationInfo.uid',
  'val bindingAfterQuery = requireNotNull(resolveBinding(entry))',
  'require(bindingAfterQuery == binding)',
  'check(!queryTimedOut)',
  'future.get(QUERY_TIMEOUT_MS, TimeUnit.MILLISECONDS)',
  'queryTimedOut = true',
  '"rusty-kiosk-launch-options-cancel"',
  'it.getType(0) == Cursor.FIELD_TYPE_INTEGER',
  'target.activityName == binding.ownerActivity'
) 'AppLaunchOptions.kt'

Assert-ContainsAll $activity @(
  'appLaunchOptions.resolveForLaunch(entry, optionId)',
  'bound.launchOptionBinding != displayedBinding || bound.launchOption != displayedOption',
  'fresh.state.binding == expectedBinding',
  'fresh.option == expectedOption',
  'fresh.option.stableDigest() == bound.candidate.binding.launchOptionDigest',
  'lastDispatchedOptionId',
  'lastDispatchedOptionPackage'
) 'RustyKioskActivity.kt'

Assert-ContainsAll $launch @(
  'AppLaunchOptionDispatchPolicy.create(entry, binding, option)',
  'guardStore.disarm("app-launch-option")',
  'plan.target.toIntent(LaunchTaskPolicy.initialFlags(LaunchKind.NORMAL))',
  'intent.putExtra(AppLaunchOptionsContract.EXTRA_LAUNCH_OPTION_ID, plan.optionId)',
  '"Dispatched ${entry.label}: ${option.displayLabel}."'
) 'LaunchController.kt'

foreach ($forbidden in @('putExtras(', 'Intent.parseUri(', '.setData(', '.setComponent(', 'Class.forName(')) {
  if ($launch.Contains($forbidden, [StringComparison]::Ordinal)) {
    throw "LaunchController.kt contains forbidden generic launch surface: $forbidden"
  }
}
if ([regex]::Matches($launch, '\.putExtra\(').Count -ne 1) {
  throw 'LaunchController.kt must contain exactly one fixed launch-option extra write.'
}

Assert-ContainsAll $cli @(
  'LAUNCH_OPTION("launch-option", CliValueRule.REQUIRED)',
  'if (kind == RustyKioskCliCommand.LAUNCH_OPTION)',
  'value?.takeIf(String::isNotBlank)',
  '"selected_launch_options_binding_sha256"',
  '"last_dispatched_option_id"',
  '"last_dispatched_option_package"'
) 'RustyKioskCliContract.kt'

if ([regex]::Matches($preview, 'id: "rusty-kiosk-launch-option-launch"').Count -ne 1) {
  throw 'Browser projection must expose one non-duplicated launch-option control id.'
}

foreach ($privateToken in @('1785693950514', 'CLI E2E Updated', 'Rusty-Symmetric-Morphovision')) {
  $match = & git -C $repoRoot grep -n --fixed-strings -- $privateToken 2>$null
  if ($LASTEXITCODE -eq 0 -and $match) {
    throw "Public Kiosk source contains private validation identity: $privateToken"
  }
}

Write-Host 'Rusty Kiosk app launch-options boundary checks passed.'
