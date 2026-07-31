Set-StrictMode -Version Latest

function Assert-RustyKioskLabsReleaseReadback {
  [CmdletBinding()]
  param(
    [Parameter(Mandatory = $true)]$Release,
    [Parameter(Mandatory = $true)][System.IO.FileInfo[]]$Assets,
    [Parameter(Mandatory = $true)][string[]]$ExpectedNames,
    [Parameter(Mandatory = $true)][string]$Repository,
    [Parameter(Mandatory = $true)][string]$Tag,
    [Parameter(Mandatory = $true)][string]$SourceRevision,
    [Parameter(Mandatory = $true)][bool]$ExpectedDraft,
    [Parameter(Mandatory = $true)][string]$Phase
  )

  if ($Repository -cnotmatch '^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$' -or
    $Tag -cnotmatch '^v(?:0|[1-9]\d*)\.(?:0|[1-9]\d*)\.(?:0|[1-9]\d*)-alpha\.(?:[1-9]|[1-8]\d|9[0-8])$' -or
    $SourceRevision -cnotmatch '^[0-9a-f]{40}$') {
    throw "$Phase Labs expected identity is malformed."
  }
  if ([int64]$Release.id -le 0 -or
    $Release.tag_name -cne $Tag -or
    $Release.target_commitish -cne $SourceRevision -or
    -not $Release.prerelease -or
    $Release.draft -ne $ExpectedDraft) {
    throw "$Phase Labs release identity did not match the immutable owner tuple."
  }

  $route = $Tag
  $expectedHtmlUrl = "https://github.com/$Repository/releases/tag/$Tag"
  if ($ExpectedDraft) {
    $htmlPattern =
      '^https://github\.com/' + [Regex]::Escape($Repository) +
      '/releases/tag/(?<route>untagged-[0-9a-f]{20})$'
    $htmlMatch = [Regex]::Match(
      [string]$Release.html_url,
      $htmlPattern,
      [Text.RegularExpressions.RegexOptions]::CultureInvariant)
    if (-not $htmlMatch.Success) {
      throw "$Phase Labs draft release route was malformed."
    }
    $route = $htmlMatch.Groups['route'].Value
  } elseif ([string]$Release.html_url -cne $expectedHtmlUrl) {
    throw "$Phase Labs live release route was malformed."
  }

  $actualNames = @($Release.assets.name | Sort-Object)
  $sortedExpectedNames = @($ExpectedNames | Sort-Object)
  if (($sortedExpectedNames -join "`n") -cne ($actualNames -join "`n")) {
    throw "$Phase Labs asset set differs from the exact owner inventory."
  }

  $assetSnapshot = @()
  $assetIds = [Collections.Generic.HashSet[long]]::new()
  foreach ($asset in $Assets) {
    $remote = @($Release.assets | Where-Object name -CEQ $asset.Name)
    $digest =
      'sha256:' +
      (Get-FileHash -LiteralPath $asset.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($remote.Count -ne 1 -or
      [int64]$remote[0].id -le 0 -or
      -not $assetIds.Add([int64]$remote[0].id) -or
      $remote[0].state -cne 'uploaded' -or
      [int64]$remote[0].size -ne $asset.Length -or
      $remote[0].digest -cne $digest) {
      throw "$Phase Labs asset digest/byte readback failed for $($asset.Name)."
    }
    $expectedDownloadUrl =
      "https://github.com/$Repository/releases/download/$route/$($asset.Name)"
    if ([string]$remote[0].browser_download_url -cne $expectedDownloadUrl) {
      throw "$Phase Labs asset download route was malformed for $($asset.Name)."
    }
    $assetSnapshot +=
      "$($remote[0].id)|$($asset.Name)|$($remote[0].size)|$($remote[0].digest)"
  }

  return [pscustomobject]@{
    ReleaseId = [int64]$Release.id
    Assets = @($assetSnapshot | Sort-Object)
    DownloadRoute = $route
  }
}

Export-ModuleMember -Function Assert-RustyKioskLabsReleaseReadback
