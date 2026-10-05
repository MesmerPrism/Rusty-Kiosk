[CmdletBinding()]
param(
  [string]$OutputRoot
)

$ErrorActionPreference = 'Stop'

if ($PSVersionTable.PSEdition -ne 'Core' -or $PSVersionTable.PSVersion -lt [version]'7.6') {
  throw 'Labs candidate preparation requires PowerShell 7.6 or newer through pwsh.'
}

$repoRoot = Split-Path -Parent $PSScriptRoot
$status = @(& git -C $repoRoot status --porcelain=v1)
if ($status.Count -ne 0) {
  throw 'A Meta Labs candidate must be built from a clean exact source commit.'
}
$branch = (& git -C $repoRoot branch --show-current).Trim()
if ([string]::IsNullOrWhiteSpace($branch)) {
  throw 'A Meta Labs candidate must be built from an attached branch.'
}

if ([string]::IsNullOrWhiteSpace($OutputRoot)) {
  $OutputRoot = Join-Path $repoRoot 'local-artifacts\launcher-release-candidates'
}
$buildMetadataPath = Join-Path $repoRoot 'local-artifacts\launcher-release-build\latest.json'
& (Join-Path $PSScriptRoot 'Build-RustyKioskLauncherRelease.ps1') `
  -Distribution LabsStore `
  -MetadataPath $buildMetadataPath | Out-Host
if ($LASTEXITCODE -ne 0) {
  throw 'The signed launcher release build failed.'
}
$build = Get-Content -Raw -LiteralPath $buildMetadataPath | ConvertFrom-Json
if (
  $build.distribution -cne 'LabsStore' -or
  $build.package -cne 'io.github.mesmerprism.rustykiosk.launcher.labstore'
) {
  throw 'The Labs candidate builder returned the wrong release identity.'
}

$stamp = (Get-Date).ToUniversalTime().ToString('yyyyMMdd-HHmmss')
$candidateDir = Join-Path $OutputRoot "labs-$stamp-signed"
New-Item -ItemType Directory -Force -Path $candidateDir | Out-Null
$candidateApk =
  Join-Path $candidateDir "rusty-kiosk-launcher-labs-v$($build.version_name)-signed.apk"
Copy-Item -LiteralPath $build.apk -Destination $candidateApk

$head = (& git -C $repoRoot rev-parse HEAD).Trim()
$tree = (& git -C $repoRoot rev-parse 'HEAD^{tree}').Trim()
$candidateHash =
  (Get-FileHash -Algorithm SHA256 -LiteralPath $candidateApk).Hash.ToLowerInvariant()
if ($candidateHash -cne [string]$build.sha256) {
  throw 'Copied candidate APK does not match the verified release build.'
}

$summary = [ordered]@{
  schema = 'rusty.kiosk.launcher.meta_labs_candidate.v1'
  created_at_utc = (Get-Date).ToUniversalTime().ToString('o')
  product_channel = 'labs'
  maturity = 'alpha'
  distribution_track = 'meta-store-app'
  upload_candidate = $true
  source = [ordered]@{
    commit = $head
    tree = $tree
    branch = $branch
    clean = $true
  }
  apk = [ordered]@{
    path = [IO.Path]::GetFullPath($candidateApk)
    sha256 = $candidateHash
    signer_sha256 = [string]$build.signer_sha256
    package = [string]$build.package
    target_package = [string]$build.target_package
    version_code = [int]$build.version_code
    version_name = [string]$build.version_name
  }
  checks = $build.checks
  distribution = [ordered]@{
    production_authorized = $false
    public_store_submission_authorized = $false
    labs_only = $true
  }
}
$summaryPath = Join-Path $candidateDir 'candidate-summary.json'
$summary | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $summaryPath -Encoding utf8NoBOM

$checklist = @(
  '# Rusty Kiosk Lab Launcher Candidate',
  '',
  "- Package: $($build.package)",
  "- Version: $($build.version_name) ($($build.version_code))",
  "- APK SHA-256: $candidateHash",
  "- Signer SHA-256: $($build.signer_sha256)",
  "- Source commit: $head",
  '- Product channel: Labs',
  '- Maturity: alpha',
  '',
  '1. Create or select the separate Rusty Kiosk Launcher app in Meta Horizon Developer Dashboard.',
  '2. Upload this APK to the separate Rusty Kiosk Lab Launcher Store app.',
  '3. Upload the APK in this directory and wait for build availability.',
  '4. Add only the intended Labs testers before broadening availability.',
  '5. Install through My Preview Apps and rerun the trusted-target smoke with:',
  "   -SkipInstall -ExpectedLauncherApkSha256 $candidateHash",
  "   -ExpectedLauncherSignerSha256 $($build.signer_sha256) -RequireNonShellInstallSource",
  '   Keep the installed identity and install-source fields in the resulting receipt.',
  '6. Do not copy this build to Production or start public Store submission.'
)
$checklist | Set-Content -LiteralPath (Join-Path $candidateDir 'UPLOAD_CHECKLIST.md') -Encoding utf8NoBOM

$summary | ConvertTo-Json -Depth 8
