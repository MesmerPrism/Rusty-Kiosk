[CmdletBinding()]
param(
  [string]$OutputRoot
)

$ErrorActionPreference = 'Stop'

if ($PSVersionTable.PSEdition -ne 'Core' -or $PSVersionTable.PSVersion -lt [version]'7.6') {
  throw 'Business candidate preparation requires PowerShell 7.6 or newer through pwsh.'
}

$repoRoot = Split-Path -Parent $PSScriptRoot
$status = @(& git -C $repoRoot status --porcelain=v1)
if ($status.Count -ne 0) {
  throw 'A Meta Business candidate must be built from a clean exact source commit.'
}
$branch = (& git -C $repoRoot branch --show-current).Trim()
if ([string]::IsNullOrWhiteSpace($branch)) {
  throw 'A Meta Business candidate must be built from an attached branch.'
}

if ([string]::IsNullOrWhiteSpace($OutputRoot)) {
  $OutputRoot =
    Join-Path $repoRoot 'local-artifacts\launcher-business-release-candidates'
}
$buildMetadataPath =
  Join-Path $repoRoot 'local-artifacts\launcher-release-build\business-latest.json'
& (Join-Path $PSScriptRoot 'Build-RustyKioskLauncherRelease.ps1') `
  -Distribution Business `
  -MetadataPath $buildMetadataPath | Out-Host
if ($LASTEXITCODE -ne 0) {
  throw 'The signed Business launcher release build failed.'
}
$build = Get-Content -Raw -LiteralPath $buildMetadataPath | ConvertFrom-Json
if (
  $build.distribution -cne 'Business' -or
  $build.package -cne 'io.github.mesmerprism.rustykiosk.launcher.business'
) {
  throw 'The Business candidate builder returned the wrong release identity.'
}

$stamp = (Get-Date).ToUniversalTime().ToString('yyyyMMdd-HHmmss')
$candidateDir = Join-Path $OutputRoot "business-$stamp-signed"
New-Item -ItemType Directory -Force -Path $candidateDir | Out-Null
$candidateApk =
  Join-Path $candidateDir "rusty-kiosk-launcher-business-v$($build.version_name)-signed.apk"
Copy-Item -LiteralPath $build.apk -Destination $candidateApk

$head = (& git -C $repoRoot rev-parse HEAD).Trim()
$tree = (& git -C $repoRoot rev-parse 'HEAD^{tree}').Trim()
$candidateHash =
  (Get-FileHash -Algorithm SHA256 -LiteralPath $candidateApk).Hash.ToLowerInvariant()
if ($candidateHash -cne [string]$build.sha256) {
  throw 'Copied Business candidate APK does not match the verified release build.'
}

$summary = [ordered]@{
  schema = 'rusty.kiosk.launcher.meta_business_candidate.v1'
  created_at_utc = (Get-Date).ToUniversalTime().ToString('o')
  distribution = 'Business'
  channel = 'Q4B_MAIN'
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
  distribution_policy = [ordered]@{
    quest_private_app_only = $true
    store_upload_authorized = $false
    public_store_submission_authorized = $false
  }
}
$summaryPath = Join-Path $candidateDir 'candidate-summary.json'
$summary | ConvertTo-Json -Depth 8 |
  Set-Content -LiteralPath $summaryPath -Encoding utf8NoBOM

$checklist = @(
  '# Rusty Kiosk Launcher Meta Business Candidate',
  '',
  "- Package: $($build.package)",
  "- Version: $($build.version_name) ($($build.version_code))",
  "- APK SHA-256: $candidateHash",
  "- Signer SHA-256: $($build.signer_sha256)",
  "- Source commit: $head",
  '- Channel: Quest Private App / Q4B_MAIN only',
  '',
  '1. Create or select the separate Rusty Kiosk Launcher Quest Private App.',
  '2. Upload this APK to the default Q4B_MAIN channel.',
  '3. Share the app with the intended Meta for Business organization.',
  '4. Accept and assign the app through Meta Admin Center.',
  '5. After managed installation, rerun the trusted-target smoke with:',
  "   -LauncherPackage $($build.package) -SkipInstall",
  "   -ExpectedLauncherApkSha256 $candidateHash",
  "   -ExpectedLauncherSignerSha256 $($build.signer_sha256) -RequireNonShellInstallSource",
  '   Keep the installed identity and install-source fields in the resulting receipt.',
  '6. Do not upload this package to a Store release channel.'
)
$checklist |
  Set-Content -LiteralPath (Join-Path $candidateDir 'UPLOAD_CHECKLIST.md') `
    -Encoding utf8NoBOM

$summary | ConvertTo-Json -Depth 8
