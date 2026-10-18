[CmdletBinding()]
param(
  [string]$RepoRoot = (Split-Path $PSScriptRoot -Parent),
  [string]$UpstreamRoot = ''
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$vendorRoot = Join-Path $RepoRoot 'vendor/quest-navigator-visibility'
$lock = Get-Content -LiteralPath (Join-Path $vendorRoot 'UPSTREAM.json') -Raw | ConvertFrom-Json -Depth 12
if ($lock.schema -cne 'rusty.kiosk.quest_visibility_vendor.v1' -or
    $lock.repository -cne 'https://github.com/MesmerPrism/rusty-quest' -or
    $lock.license -cne 'AGPL-3.0-or-later' -or $lock.revision -cnotmatch '^[0-9a-f]{40}$' -or
    $lock.tree -cnotmatch '^[0-9a-f]{40}$' -or $lock.files.Count -ne 14) {
  throw 'Unsupported Quest visibility provenance inventory.'
}
$expected = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
foreach ($row in $lock.files) {
  $relative = [string]$row.vendored_path
  if ($relative -match '\\|:|^/|(^|/)\.{1,2}(/|$)' -or -not $expected.Add($relative)) {
    throw 'Noncanonical or duplicate vendored path.'
  }
  $path = Join-Path $vendorRoot $relative
  if (-not (Test-Path -LiteralPath $path -PathType Leaf) -or
      (Get-Item -LiteralPath $path).Length -ne $row.bytes -or
      (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant() -cne $row.sha256) {
    throw "Vendored source differs from pinned inventory: $relative"
  }
  if ($UpstreamRoot) {
    $sourceRelative = [string]$row.source_path
    if ($sourceRelative -match '\\|:|^/|(^|/)\.{1,2}(/|$)') { throw 'Noncanonical upstream path.' }
    $source = Join-Path $UpstreamRoot $sourceRelative
    if ((Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash.ToLowerInvariant() -cne $row.sha256) {
      throw "Canonical Quest source differs from inventory: $sourceRelative"
    }
  }
}
[void]$expected.Add('UPSTREAM.json')
[void]$expected.Add('README.md')
$actual = @(Get-ChildItem -LiteralPath $vendorRoot -Recurse -File)
if ($actual.Count -ne $expected.Count) { throw 'Unexpected vendor payload or missing file.' }
foreach ($file in $actual) {
  $relative = [IO.Path]::GetRelativePath($vendorRoot, $file.FullName).Replace('\', '/')
  if (-not $expected.Contains($relative)) { throw "Unexpected vendor file: $relative" }
}
if ($UpstreamRoot) {
  $revision = (& git -C $UpstreamRoot rev-parse HEAD).Trim()
  if ($LASTEXITCODE -ne 0 -or $revision -cne $lock.revision) { throw 'Upstream is not the exact selected commit.' }
  $tree = (& git -C $UpstreamRoot rev-parse 'HEAD^{tree}').Trim()
  if ($LASTEXITCODE -ne 0 -or $tree -cne $lock.tree) { throw 'Upstream tree differs.' }
  $dirty = & git -C $UpstreamRoot status --porcelain=v1 -- $lock.component LICENSE
  if ($LASTEXITCODE -ne 0 -or $dirty) { throw 'Upstream component is dirty.' }
}
[ordered]@{schema='rusty.kiosk.quest_visibility_vendor_check.v1';result='pass';
  revision=$lock.revision;verified_files=$lock.files.Count;canonical_checkout_checked=[bool]$UpstreamRoot;
  device_or_apk_work=$false} | ConvertTo-Json
