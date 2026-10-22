[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path -LiteralPath (Split-Path -Parent $PSScriptRoot)).Path
$source = Join-Path $repoRoot 'app/src/main/java/io/github/mesmerprism/rustykiosk'
$endpoint = Get-Content -Raw (Join-Path $source 'DirectOperatorV2Endpoint.kt')
$owner = Get-Content -Raw (Join-Path $source 'KioskCatalogOwner.kt')
$service = Get-Content -Raw (Join-Path $source 'OperatorBridgeService.kt')
$activity = Get-Content -Raw (Join-Path $source 'RustyKioskActivity.kt')
if ($owner -match 'startActivity|LaunchController|GuardStateStore|Installer|SetupHelper|ensureExists|loadDocument') {
  throw 'Passive catalogue collection acquired an effectful dependency.'
}
foreach ($required in @('DirectOperatorV2RoutePolicy.CONTRACT_PATH',
    'DirectOperatorV2RoutePolicy.SESSION_OPEN_PATH', 'DirectOperatorV2RoutePolicy.CATALOG_SUMMARY_PATH',
    'validateForDispatch', 'sealResponse', 'currentGeneration()')) {
  if (-not $endpoint.Contains($required)) { throw "Catalogue endpoint lost $required" }
}
if ($endpoint -match 'CATALOG_DETAIL_PATH|LAUNCH_ISSUE_PATH|LAUNCH_EXECUTE_PATH') {
  throw 'The summary endpoint acquired detail or launch dispatch.'
}
if (-not $service.Contains('v2HttpAdapter.serve') -or -not $service.Contains('OperatorBridgeAuth.parse')) {
  throw 'Both the bounded v2 HTTP adapter and current authenticated v1 routes must remain connected.'
}
$pending = [regex]::Match($activity,
  '(?s)RustyKioskCliCommand.REQUEST_CATALOG_SUMMARY -> \{(.*?)\n      \}').Groups[1].Value
if (-not $pending.Contains('userControlsOpen = true') -or
    -not $pending.Contains('cliOutcome(true, false,') -or $pending.Contains('setV2CatalogSummaryGranted')) {
  throw 'A host catalogue request must remain pending wearer consent.'
}
Write-Output 'Encrypted catalogue summary source boundaries passed.'
