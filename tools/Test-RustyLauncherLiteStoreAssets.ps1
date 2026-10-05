[CmdletBinding()]
param(
  [Parameter(Mandatory = $true)][string]$CandidateManifestPath,
  [Parameter(Mandatory = $true)][string]$DeviceReceiptPath,
  [Parameter(Mandatory = $true)][string]$CaptureReceiptPath,
  [Parameter(Mandatory = $true)][string]$ScreenshotDirectory,
  [string]$OutputPath
)

$ErrorActionPreference = 'Stop'
$candidatePath = [IO.Path]::GetFullPath($CandidateManifestPath)
$receiptPath = [IO.Path]::GetFullPath($DeviceReceiptPath)
$captureReceiptPath = [IO.Path]::GetFullPath($CaptureReceiptPath)
$screensPath = [IO.Path]::GetFullPath($ScreenshotDirectory)
$candidate = Get-Content -Raw -LiteralPath $candidatePath | ConvertFrom-Json
$receipt = Get-Content -Raw -LiteralPath $receiptPath | ConvertFrom-Json
$captureReceipt = Get-Content -Raw -LiteralPath $captureReceiptPath | ConvertFrom-Json

function Assert-ExactProperties {
  param(
    [Parameter(Mandatory = $true)]$Value,
    [Parameter(Mandatory = $true)][string[]]$Names,
    [Parameter(Mandatory = $true)][string]$Label
  )
  $actual = @($Value.PSObject.Properties.Name | Sort-Object)
  $expected = @($Names | Sort-Object)
  if (($actual -join "`n") -cne ($expected -join "`n")) {
    throw "$Label does not have the fixed property set."
  }
}

Assert-ExactProperties $candidate @('schema','created_at_utc','source','app','apk','checks') 'Candidate'
Assert-ExactProperties $candidate.source @('commit','tree','worktree_clean') 'Candidate source'
Assert-ExactProperties $candidate.app @(
  'meta_application_id','channel','package','version_name','version_code',
  'runtime_mode','companion_required'
) 'Candidate app'
Assert-ExactProperties $candidate.apk @(
  'name','bytes','sha256','signer_sha256','signer_policy_sha256'
) 'Candidate APK'
Assert-ExactProperties $captureReceipt @('schema','result','target','capture','flows','screenshots') 'Capture receipt'
Assert-ExactProperties $captureReceipt.target @(
  'package','version_name','version_code','debuggable','apk_bytes','apk_sha256','signer_sha256'
) 'Capture target'
Assert-ExactProperties $captureReceipt.capture @('width','height','source','transform') 'Capture method'
Assert-ExactProperties $captureReceipt.flows @(
  'catalog','search_tag','favorite','wifi_preflight','launch'
) 'Capture flows'
if (@($captureReceipt.screenshots).Count -ne 5) {
  throw 'Capture receipt must contain exactly five screenshots.'
}
foreach ($captureScreenshot in @($captureReceipt.screenshots)) {
  Assert-ExactProperties $captureScreenshot @('name','bytes','sha256') 'Capture screenshot'
}
if ($candidate.schema -cne 'rusty.kiosk.launcher_lite.meta_store_candidate.v1' -or
    $candidate.source.commit -notmatch '^[0-9a-f]{40}$' -or
    $candidate.source.tree -notmatch '^[0-9a-f]{40}$' -or
    $candidate.source.worktree_clean -ne $true -or
    $candidate.app.meta_application_id -cne '1241943475671333' -or
    $candidate.app.channel -cne 'Production' -or
    $candidate.app.package -cne 'io.github.mesmerprism.rustykiosk.launcher' -or
    $candidate.app.version_name -cne '0.3.0' -or
    [int]$candidate.app.version_code -ne 3 -or
    $candidate.app.runtime_mode -cne 'standalone-lite-hybrid' -or
    $candidate.app.companion_required -ne $false -or
    $candidate.apk.name -cne 'rusty-launcher-lite-v0.3.0-signed.apk' -or
    $candidate.apk.sha256 -notmatch '^[0-9a-f]{64}$' -or
    $candidate.apk.signer_sha256 -notmatch '^[0-9a-f]{64}$' -or
    $candidate.apk.signer_policy_sha256 -notmatch '^[0-9a-f]{64}$' -or
    @($candidate.checks.PSObject.Properties | Where-Object { -not [bool]$_.Value }).Count -ne 0) {
  throw 'The candidate manifest is not the fixed Launcher Lite Store candidate.'
}
$stagedApk = Join-Path (Split-Path -Parent $candidatePath) ([string]$candidate.apk.name)
if (-not (Test-Path -LiteralPath $stagedApk -PathType Leaf) -or
    (Get-Item -LiteralPath $stagedApk).Length -ne [int64]$candidate.apk.bytes -or
    (Get-FileHash -LiteralPath $stagedApk -Algorithm SHA256).Hash.ToLowerInvariant() -cne
      [string]$candidate.apk.sha256) {
  throw 'The staged Store APK does not match the candidate manifest.'
}
$signerPolicyPath = Join-Path (Split-Path -Parent $PSScriptRoot) 'release\launcher-release-signer-policy.v1.json'
if ((Get-FileHash -LiteralPath $signerPolicyPath -Algorithm SHA256).Hash.ToLowerInvariant() -cne
    [string]$candidate.apk.signer_policy_sha256) {
  throw 'The candidate signer policy is not the exact reviewed repository policy.'
}
if ($receipt.schema -cne 'rusty.kiosk.launcher_lite.device_validation.v1' -or
    $receipt.result -cne 'pass' -or
    $receipt.apk.sha256 -cne $candidate.apk.sha256 -or
    [int64]$receipt.apk.bytes -ne [int64]$candidate.apk.bytes -or
    $receipt.apk.signer_sha256 -cne $candidate.apk.signer_sha256) {
  throw 'The device receipt does not accept the exact candidate APK.'
}
if ($captureReceipt.schema -cne 'rusty.kiosk.launcher_lite.store_capture.v1' -or
    $captureReceipt.result -cne 'pass' -or
    $captureReceipt.target.package -cne $candidate.app.package -or
    $captureReceipt.target.version_name -cne $candidate.app.version_name -or
    [int64]$captureReceipt.target.version_code -ne [int64]$candidate.app.version_code -or
    $captureReceipt.target.debuggable -ne $false -or
    [int64]$captureReceipt.target.apk_bytes -ne [int64]$candidate.apk.bytes -or
    $captureReceipt.target.apk_sha256 -cne $candidate.apk.sha256 -or
    $captureReceipt.target.signer_sha256 -cne $candidate.apk.signer_sha256 -or
    [int]$captureReceipt.capture.width -ne 2560 -or
    [int]$captureReceipt.capture.height -ne 1440 -or
    $captureReceipt.capture.source -cne 'production-activity-decor-view' -or
    $captureReceipt.capture.transform -cne 'aspect-fit-neutral-matte-no-overlay' -or
    @($captureReceipt.flows.PSObject.Properties | Where-Object { $_.Value -cne 'pass' }).Count -ne 0) {
  throw 'The on-device Store capture receipt does not bind the exact non-debuggable candidate.'
}

$roles = [ordered]@{
  'screenshot-01-catalog-home.png' = 'catalogue'
  'screenshot-02-search-tags.png' = 'search-tags'
  'screenshot-03-app-details.png' = 'app-details'
  'screenshot-04-wifi-preflight.png' = 'wifi-preflight'
  'screenshot-05-favorites.png' = 'favorites'
}
$files = @(Get-ChildItem -LiteralPath $screensPath -File -Filter '*.png')
if ($files.Count -ne $roles.Count) {
  throw "Expected exactly $($roles.Count) PNG screenshots, got $($files.Count)."
}

function Read-PngHeader {
  param([Parameter(Mandatory = $true)][string]$Path)

  $bytes = [IO.File]::ReadAllBytes($Path)
  if ($bytes.Length -lt 33) {
    throw "Not a valid PNG: $Path"
  }
  $signature = @(137,80,78,71,13,10,26,10)
  for ($index = 0; $index -lt $signature.Count; $index += 1) {
    if ($bytes[$index] -ne $signature[$index]) {
      throw "Not a valid PNG: $Path"
    }
  }
  $width =
    ([uint32]$bytes[16] -shl 24) -bor
    ([uint32]$bytes[17] -shl 16) -bor
    ([uint32]$bytes[18] -shl 8) -bor
    [uint32]$bytes[19]
  $height =
    ([uint32]$bytes[20] -shl 24) -bor
    ([uint32]$bytes[21] -shl 16) -bor
    ([uint32]$bytes[22] -shl 8) -bor
    [uint32]$bytes[23]
  [pscustomobject]@{
    width = $width
    height = $height
    bit_depth = [int]$bytes[24]
    color_type = [int]$bytes[25]
  }
}

$entries = @()
$hashes = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
foreach ($entry in $roles.GetEnumerator()) {
  $path = Join-Path $screensPath $entry.Key
  if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
    throw "Missing Store screenshot: $($entry.Key)"
  }
  $header = Read-PngHeader -Path $path
  if ($header.width -ne 2560 -or $header.height -ne 1440 -or
      $header.bit_depth -ne 8 -or $header.color_type -ne 2) {
    throw "$($entry.Key) must be an 8-bit RGB 2560x1440 PNG without alpha."
  }
  $file = Get-Item -LiteralPath $path
  $hash = (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant()
  $captureEntry = @($captureReceipt.screenshots | Where-Object { $_.name -ceq $entry.Key })
  if ($captureEntry.Count -ne 1 -or
      [int64]$captureEntry[0].bytes -ne [int64]$file.Length -or
      $captureEntry[0].sha256 -cne $hash) {
    throw "$($entry.Key) does not match the on-device capture receipt."
  }
  if (-not $hashes.Add($hash)) {
    throw 'Every Store screenshot must have a distinct SHA-256 digest.'
  }
  $entries += [ordered]@{
    role = [string]$entry.Value
    name = [string]$entry.Key
    bytes = $file.Length
    sha256 = $hash
    width = $header.width
    height = $header.height
    bit_depth = $header.bit_depth
    color_type = 'truecolor-rgb'
  }
}

$manifest = [ordered]@{
  schema = 'rusty.kiosk.launcher_lite.store_assets.v1'
  created_at_utc = (Get-Date).ToUniversalTime().ToString('o')
  candidate_manifest_sha256 =
    (Get-FileHash -LiteralPath $candidatePath -Algorithm SHA256).Hash.ToLowerInvariant()
  device_receipt_sha256 =
    (Get-FileHash -LiteralPath $receiptPath -Algorithm SHA256).Hash.ToLowerInvariant()
  capture_receipt_sha256 =
    (Get-FileHash -LiteralPath $captureReceiptPath -Algorithm SHA256).Hash.ToLowerInvariant()
  apk_sha256 = [string]$candidate.apk.sha256
  screenshot_policy = 'on-device-production-view-no-overlay-or-added-marketing-elements'
  screenshots = $entries
}
if (-not [string]::IsNullOrWhiteSpace($OutputPath)) {
  $output = [IO.Path]::GetFullPath($OutputPath)
  New-Item -ItemType Directory -Force -Path (Split-Path -Parent $output) | Out-Null
  $manifest | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $output -Encoding utf8NoBOM
}
$manifest | ConvertTo-Json -Depth 8
