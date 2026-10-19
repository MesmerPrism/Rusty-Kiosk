[CmdletBinding()]
param([string]$RepoRoot = (Split-Path $PSScriptRoot -Parent), [string]$UpstreamRoot = '')
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$vendorRoot = Join-Path $RepoRoot 'vendor/quest-shell-context'
$lock = Get-Content -Raw -LiteralPath (Join-Path $vendorRoot 'UPSTREAM.json') | ConvertFrom-Json -Depth 12
if ($lock.schema -cne 'rusty.kiosk.quest_shell_context_vendor.v1' -or
    $lock.repository -cne 'https://github.com/MesmerPrism/rusty-quest' -or
    $lock.component -cne 'apps/shell-recenter-android' -or $lock.license -cne 'AGPL-3.0-or-later' -or
    $lock.revision -cnotmatch '^[0-9a-f]{40}$' -or $lock.tree -cnotmatch '^[0-9a-f]{40}$' -or $lock.files.Count -ne 2) {
  throw 'Unsupported Quest shell context provenance inventory.'
}
$factory = 'src/main/java/io/github/mesmerprism/rustyquest/shellrecenter/ShellApplicationContext.java'
$mapping = @{ $factory = 'apps/shell-recenter-android/' + $factory; LICENSE = 'LICENSE' }
$seen = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
foreach ($row in $lock.files) {
  $relative = [string]$row.vendored_path
  if (-not $mapping.Contains($relative) -or -not $seen.Add($relative) -or $row.source_path -cne $mapping[$relative] -or
      $row.sha256 -cnotmatch '^[0-9a-f]{64}$' -or $row.bytes -le 0) { throw 'Unexpected or duplicate shell context source.' }
  $path = Join-Path $vendorRoot $relative
  if (-not (Test-Path -LiteralPath $path -PathType Leaf) -or (Get-Item -LiteralPath $path).Length -ne $row.bytes -or
      (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant() -cne $row.sha256) { throw "Vendored shell context changed: $relative" }
  if ($UpstreamRoot -and (Get-FileHash -LiteralPath (Join-Path $UpstreamRoot $row.source_path) -Algorithm SHA256).Hash.ToLowerInvariant() -cne $row.sha256) {
    throw 'Canonical Quest shell context source/license differs.'
  }
}
[void]$seen.Add('README.md'); [void]$seen.Add('UPSTREAM.json')
$files = @(Get-ChildItem -LiteralPath $vendorRoot -Recurse -File)
if ($files.Count -ne $seen.Count) { throw 'Unexpected shell context vendor payload.' }
foreach ($file in $files) {
  if (-not $seen.Contains([IO.Path]::GetRelativePath($vendorRoot, $file.FullName).Replace('\','/'))) { throw 'Unlisted shell context vendor payload.' }
}
if (Test-Path -LiteralPath (Join-Path $RepoRoot 'shell-companion/src/main/java/io/github/mesmerprism/rustykiosk/shellcompanion/ShellApplicationContext.java')) {
  throw 'The companion must consume the shared factory rather than own a duplicate implementation.'
}
if ($UpstreamRoot) {
  if ((& git -C $UpstreamRoot rev-parse HEAD).Trim() -cne $lock.revision -or
      (& git -C $UpstreamRoot rev-parse 'HEAD^{tree}').Trim() -cne $lock.tree) { throw 'Upstream is not the exact selected Quest commit/tree.' }
  $dirty = & git -C $UpstreamRoot status --porcelain=v1 -- $lock.component LICENSE
  if ($LASTEXITCODE -ne 0 -or $dirty) { throw 'Upstream shell context component is dirty.' }
}
[ordered]@{schema='rusty.kiosk.quest_shell_context_vendor_check.v1'; result='pass'; revision=$lock.revision;
  verified_files=2; canonical_checkout_checked=[bool]$UpstreamRoot; device_or_apk_work=$false} | ConvertTo-Json
