[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
Import-Module (Join-Path $repoRoot 'tools\RustyKiosk.LabsReleaseReadback.psm1') -Force

$tempRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath())
$testRoot = Join-Path $tempRoot ('rusty-kiosk-labs-readback-' + [guid]::NewGuid().ToString('N'))
$testRoot = [IO.Path]::GetFullPath($testRoot)
if (-not $testRoot.StartsWith($tempRoot, [StringComparison]::OrdinalIgnoreCase)) {
  throw 'Synthetic Labs readback root escaped the system temporary directory.'
}

function Assert-Rejected {
  param(
    [Parameter(Mandatory = $true)][scriptblock]$Action,
    [Parameter(Mandatory = $true)][string]$Name
  )
  try {
    & $Action
  } catch {
    return
  }
  throw "Damaged Labs readback was accepted: $Name"
}

function Copy-ReleaseObject {
  param([Parameter(Mandatory = $true)]$Release)
  return ($Release | ConvertTo-Json -Depth 8 | ConvertFrom-Json)
}

try {
  New-Item -ItemType Directory -Path $testRoot | Out-Null
  $assetPaths = @(
    (Join-Path $testRoot 'bundle-manifest.json')
    (Join-Path $testRoot 'rusty-kiosk.apk')
  )
  [IO.File]::WriteAllText($assetPaths[0], '{"schema":"synthetic"}')
  [IO.File]::WriteAllBytes($assetPaths[1], [byte[]](0, 1, 2, 3, 4))
  $assets = @(Get-Item -LiteralPath $assetPaths | Sort-Object Name)
  $expectedNames = @($assets.Name | Sort-Object)
  $repository = 'MesmerPrism/Rusty-Kiosk'
  $tag = 'v0.6.6-alpha.6'
  $sourceRevision = '1234567890abcdef1234567890abcdef12345678'
  $draftRoute = 'untagged-0123456789abcdefabcd'
  $remoteAssets = @()
  $assetId = 700
  foreach ($asset in $assets) {
    $remoteAssets += [pscustomobject]@{
      id = $assetId
      name = $asset.Name
      size = $asset.Length
      state = 'uploaded'
      digest = 'sha256:' +
        (Get-FileHash -LiteralPath $asset.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
      browser_download_url =
        "https://github.com/$repository/releases/download/$draftRoute/$($asset.Name)"
    }
    $assetId++
  }
  $draft = [pscustomobject]@{
    id = 42
    tag_name = $tag
    target_commitish = $sourceRevision
    prerelease = $true
    draft = $true
    html_url = "https://github.com/$repository/releases/tag/$draftRoute"
    assets = $remoteAssets
  }
  $draftResult = Assert-RustyKioskLabsReleaseReadback `
    -Release $draft -Assets $assets -ExpectedNames $expectedNames `
    -Repository $repository -Tag $tag -SourceRevision $sourceRevision `
    -ExpectedDraft $true -Phase 'Synthetic draft'
  if ($draftResult.ReleaseId -ne 42 -or $draftResult.DownloadRoute -cne $draftRoute) {
    throw 'Valid synthetic draft returned the wrong bound identity.'
  }

  $live = Copy-ReleaseObject $draft
  $live.draft = $false
  $live.html_url = "https://github.com/$repository/releases/tag/$tag"
  foreach ($asset in $live.assets) {
    $asset.browser_download_url =
      "https://github.com/$repository/releases/download/$tag/$($asset.name)"
  }
  $liveResult = Assert-RustyKioskLabsReleaseReadback `
    -Release $live -Assets $assets -ExpectedNames $expectedNames `
    -Repository $repository -Tag $tag -SourceRevision $sourceRevision `
    -ExpectedDraft $false -Phase 'Synthetic live'
  if ($liveResult.DownloadRoute -cne $tag) {
    throw 'Valid synthetic live release returned the wrong tag route.'
  }

  $badLiveHtml = Copy-ReleaseObject $live
  $badLiveHtml.html_url =
    "https://github.com/$repository/releases/tag/$draftRoute"
  Assert-Rejected -Name 'untagged live HTML route' -Action {
    Assert-RustyKioskLabsReleaseReadback `
      -Release $badLiveHtml -Assets $assets -ExpectedNames $expectedNames `
      -Repository $repository -Tag $tag -SourceRevision $sourceRevision `
      -ExpectedDraft $false -Phase 'Damaged live HTML'
  }

  $badHtml = Copy-ReleaseObject $draft
  $badHtml.html_url =
    'https://github.com/MesmerPrism/Rusty-Kiosk/releases/tag/untagged-fedcba9876543210abcd'
  Assert-Rejected -Name 'draft HTML/asset route mismatch' -Action {
    Assert-RustyKioskLabsReleaseReadback `
      -Release $badHtml -Assets $assets -ExpectedNames $expectedNames `
      -Repository $repository -Tag $tag -SourceRevision $sourceRevision `
      -ExpectedDraft $true -Phase 'Damaged draft HTML'
  }

  $mixedDraft = Copy-ReleaseObject $draft
  $mixedDraft.assets[0].browser_download_url =
    "https://github.com/$repository/releases/download/untagged-fedcba9876543210abcd/$($mixedDraft.assets[0].name)"
  Assert-Rejected -Name 'mixed draft asset route' -Action {
    Assert-RustyKioskLabsReleaseReadback `
      -Release $mixedDraft -Assets $assets -ExpectedNames $expectedNames `
      -Repository $repository -Tag $tag -SourceRevision $sourceRevision `
      -ExpectedDraft $true -Phase 'Damaged mixed draft'
  }

  $untaggedLive = Copy-ReleaseObject $live
  $untaggedLive.assets[0].browser_download_url =
    "https://github.com/$repository/releases/download/$draftRoute/$($untaggedLive.assets[0].name)"
  Assert-Rejected -Name 'untagged live asset route' -Action {
    Assert-RustyKioskLabsReleaseReadback `
      -Release $untaggedLive -Assets $assets -ExpectedNames $expectedNames `
      -Repository $repository -Tag $tag -SourceRevision $sourceRevision `
      -ExpectedDraft $false -Phase 'Damaged live route'
  }

  $wrongSource = Copy-ReleaseObject $draft
  $wrongSource.target_commitish = 'abcdef1234567890abcdef1234567890abcdef12'
  Assert-Rejected -Name 'wrong target commitish' -Action {
    Assert-RustyKioskLabsReleaseReadback `
      -Release $wrongSource -Assets $assets -ExpectedNames $expectedNames `
      -Repository $repository -Tag $tag -SourceRevision $sourceRevision `
      -ExpectedDraft $true -Phase 'Damaged source'
  }

  $badIdentity = Copy-ReleaseObject $draft
  $badIdentity.id = 0
  Assert-Rejected -Name 'nonpositive release ID' -Action {
    Assert-RustyKioskLabsReleaseReadback `
      -Release $badIdentity -Assets $assets -ExpectedNames $expectedNames `
      -Repository $repository -Tag $tag -SourceRevision $sourceRevision `
      -ExpectedDraft $true -Phase 'Damaged release ID'
  }

  $wrongFlags = Copy-ReleaseObject $draft
  $wrongFlags.prerelease = $false
  Assert-Rejected -Name 'wrong prerelease flag' -Action {
    Assert-RustyKioskLabsReleaseReadback `
      -Release $wrongFlags -Assets $assets -ExpectedNames $expectedNames `
      -Repository $repository -Tag $tag -SourceRevision $sourceRevision `
      -ExpectedDraft $true -Phase 'Damaged release flags'
  }

  $duplicateAssetId = Copy-ReleaseObject $draft
  $duplicateAssetId.assets[1].id = $duplicateAssetId.assets[0].id
  Assert-Rejected -Name 'duplicate asset ID' -Action {
    Assert-RustyKioskLabsReleaseReadback `
      -Release $duplicateAssetId -Assets $assets -ExpectedNames $expectedNames `
      -Repository $repository -Tag $tag -SourceRevision $sourceRevision `
      -ExpectedDraft $true -Phase 'Damaged asset IDs'
  }

  $wrongAsset = Copy-ReleaseObject $draft
  $wrongAsset.assets[0].digest = 'sha256:' + ('0' * 64)
  Assert-Rejected -Name 'wrong asset digest' -Action {
    Assert-RustyKioskLabsReleaseReadback `
      -Release $wrongAsset -Assets $assets -ExpectedNames $expectedNames `
      -Repository $repository -Tag $tag -SourceRevision $sourceRevision `
      -ExpectedDraft $true -Phase 'Damaged asset digest'
  }

  $extraAsset = Copy-ReleaseObject $draft
  $extraAsset.assets += [pscustomobject]@{
    id = 999
    name = 'unexpected.apk'
    size = 1
    state = 'uploaded'
    digest = 'sha256:' + ('0' * 64)
    browser_download_url =
      "https://github.com/$repository/releases/download/$draftRoute/unexpected.apk"
  }
  Assert-Rejected -Name 'extra asset' -Action {
    Assert-RustyKioskLabsReleaseReadback `
      -Release $extraAsset -Assets $assets -ExpectedNames $expectedNames `
      -Repository $repository -Tag $tag -SourceRevision $sourceRevision `
      -ExpectedDraft $true -Phase 'Damaged asset inventory'
  }

  Assert-Rejected -Name 'out-of-range alpha suffix' -Action {
    Assert-RustyKioskLabsReleaseReadback `
      -Release $draft -Assets $assets -ExpectedNames $expectedNames `
      -Repository $repository -Tag 'v0.6.6-alpha.99' `
      -SourceRevision $sourceRevision -ExpectedDraft $true `
      -Phase 'Damaged alpha range'
  }

  Assert-Rejected -Name 'malformed repository owner' -Action {
    Assert-RustyKioskLabsReleaseReadback `
      -Release $draft -Assets $assets -ExpectedNames $expectedNames `
      -Repository '../Rusty-Kiosk' -Tag $tag `
      -SourceRevision $sourceRevision -ExpectedDraft $true `
      -Phase 'Damaged repository'
  }
} finally {
  if (Test-Path -LiteralPath $testRoot -PathType Container) {
    $resolvedTestRoot = [IO.Path]::GetFullPath((Resolve-Path -LiteralPath $testRoot).Path)
    if (-not $resolvedTestRoot.StartsWith($tempRoot, [StringComparison]::OrdinalIgnoreCase) -or
      $resolvedTestRoot -ceq $tempRoot) {
      throw 'Refusing to remove an unsafe synthetic Labs readback directory.'
    }
    Remove-Item -LiteralPath $resolvedTestRoot -Recurse -Force
  }
}

Write-Output 'Rusty Kiosk Labs release-readback tests passed.'
