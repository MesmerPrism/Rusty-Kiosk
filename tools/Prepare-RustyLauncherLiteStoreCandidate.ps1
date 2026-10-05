[CmdletBinding()]
param(
  [string]$OutputRoot
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
if ([string]::IsNullOrWhiteSpace($OutputRoot)) {
  $OutputRoot = Join-Path $repoRoot 'local-artifacts\launcher-lite-store-release-candidates'
}

Push-Location $repoRoot
try {
  $status = @(& git status --porcelain=v1 --untracked-files=all)
  if ($LASTEXITCODE -ne 0 -or $status.Count -ne 0) {
    throw 'The Store candidate requires a clean exact Git worktree.'
  }
  $sourceCommit = (& git rev-parse HEAD).Trim()
  $sourceTree = (& git rev-parse 'HEAD^{tree}').Trim()
  if ($LASTEXITCODE -ne 0 -or $sourceCommit -notmatch '^[0-9a-f]{40}$' -or
      $sourceTree -notmatch '^[0-9a-f]{40}$') {
    throw 'Unable to bind the exact source commit and tree.'
  }

  $metadataPath = Join-Path $repoRoot 'local-artifacts\launcher-lite-release-build\latest.json'
  & pwsh -NoProfile -ExecutionPolicy Bypass -File `
    (Join-Path $PSScriptRoot 'Build-RustyKioskLauncherRelease.ps1') `
    -Distribution Store `
    -MetadataPath $metadataPath | Out-Host
  if ($LASTEXITCODE -ne 0) {
    throw 'The signed Launcher Lite release build failed.'
  }
  $build = Get-Content -Raw -LiteralPath $metadataPath | ConvertFrom-Json
  $signerPolicyPath = Join-Path $repoRoot 'release\launcher-release-signer-policy.v1.json'
  $signerPolicy = Get-Content -Raw -LiteralPath $signerPolicyPath | ConvertFrom-Json
  if (
    $build.package -cne 'io.github.mesmerprism.rustykiosk.launcher' -or
    $build.version_name -cne '0.3.0' -or
    [int]$build.version_code -ne 3 -or
    $build.runtime_mode -cne 'standalone-lite-hybrid' -or
    $build.companion_required -ne $false -or
    $build.signer_sha256 -cne [string]$signerPolicy.signer_sha256 -or
    @($build.checks.PSObject.Properties | Where-Object { -not [bool]$_.Value }).Count -ne 0
  ) {
    throw 'The release build does not match the fixed standalone Store identity.'
  }

  $stamp = (Get-Date).ToUniversalTime().ToString('yyyyMMddTHHmmssZ')
  $candidateDir = Join-Path $OutputRoot "v0.3.0-$stamp"
  New-Item -ItemType Directory -Force -Path $candidateDir | Out-Null
  $apkName = 'rusty-launcher-lite-v0.3.0-signed.apk'
  $apkPath = Join-Path $candidateDir $apkName
  Copy-Item -LiteralPath $build.apk -Destination $apkPath
  $apk = Get-Item -LiteralPath $apkPath
  $apkHash = (Get-FileHash -LiteralPath $apk.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
  if ($apkHash -cne $build.sha256) {
    throw 'The staged APK does not match the inspected release output.'
  }

  $candidate = [ordered]@{
    schema = 'rusty.kiosk.launcher_lite.meta_store_candidate.v1'
    created_at_utc = (Get-Date).ToUniversalTime().ToString('o')
    source = [ordered]@{
      commit = $sourceCommit
      tree = $sourceTree
      worktree_clean = $true
    }
    app = [ordered]@{
      meta_application_id = '1241943475671333'
      channel = 'Production'
      package = [string]$build.package
      version_name = [string]$build.version_name
      version_code = [int]$build.version_code
      runtime_mode = [string]$build.runtime_mode
      companion_required = [bool]$build.companion_required
    }
    apk = [ordered]@{
      name = $apkName
      bytes = $apk.Length
      sha256 = $apkHash
      signer_sha256 = [string]$build.signer_sha256
      signer_policy_sha256 =
        (Get-FileHash -LiteralPath $signerPolicyPath -Algorithm SHA256).Hash.ToLowerInvariant()
    }
    checks = $build.checks
  }
  $candidatePath = Join-Path $candidateDir 'candidate-manifest.json'
  $candidate | ConvertTo-Json -Depth 10 |
    Set-Content -LiteralPath $candidatePath -Encoding utf8NoBOM

  @(
    'This candidate updates the published standalone Lite app with hybrid 2D and immersive presentation.'
    'It does not require Rusty Kiosk or companion software, Developer Mode, ADB/USB, PC software, an account, login, or credentials.'
    'Suggested flow: search; open details; add/remove a favorite and tag; set a Wi-Fi preference; use standard Wi-Fi settings or Cancel on mismatch; launch an installed app; return and verify local state.'
    'The app contains no ads, purchases, networking, analytics, or user-data transmission.'
    'A production Activity render qualifies the flat surface only; immersive compositor, pointer, keyboard and mode transitions need separate exact-candidate headset validation.'
    'All five screenshots must be 2560x1440 on-device renders of the production Activity from this exact non-debuggable build, with only neutral aspect-fit matte and no added artwork, labels, or marketing overlays.'
  ) | Set-Content -LiteralPath (Join-Path $candidateDir 'reviewer-notes.txt') -Encoding utf8NoBOM

  $candidate | ConvertTo-Json -Depth 10
} finally {
  Pop-Location
}
