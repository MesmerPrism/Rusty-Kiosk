[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)

function Get-UniqueLineIndex {
  param(
    [Parameter(Mandatory = $true)][AllowEmptyString()][string[]]$Lines,
    [Parameter(Mandatory = $true)][string]$Pattern,
    [Parameter(Mandatory = $true)][string]$Name
  )
  $matchingIndexes = @()
  for ($index = 0; $index -lt $Lines.Count; $index++) {
    if ($Lines[$index] -match $Pattern) {
      $matchingIndexes += $index
    }
  }
  if ($matchingIndexes.Count -ne 1) {
    throw "Expected exactly one $Name line; found $($matchingIndexes.Count)."
  }
  return $matchingIndexes[0]
}

function Assert-NoExpressionsInRunBlocks {
  param(
    [Parameter(Mandatory = $true)][AllowEmptyString()][string[]]$Lines,
    [Parameter(Mandatory = $true)][string]$Name
  )
  for ($index = 0; $index -lt $Lines.Count; $index++) {
    $match = [Regex]::Match($Lines[$index], '^(?<indent>\s*)run:\s*\|\s*$')
    if (-not $match.Success) {
      continue
    }
    $baseIndent = $match.Groups['indent'].Value.Length
    for ($cursor = $index + 1; $cursor -lt $Lines.Count; $cursor++) {
      $line = $Lines[$cursor]
      if ([string]::IsNullOrWhiteSpace($line)) {
        continue
      }
      $lineIndent = $line.Length - $line.TrimStart().Length
      if ($lineIndent -le $baseIndent) {
        break
      }
      if ($line.Contains('${{', [StringComparison]::Ordinal)) {
        throw "$Name embeds a GitHub expression in executable shell source at line $($cursor + 1)."
      }
    }
  }
}

function Assert-ReleaseWorkflow {
  param(
    [Parameter(Mandatory = $true)][string]$Path,
    [Parameter(Mandatory = $true)][string]$Channel,
    [Parameter(Mandatory = $true)][string]$Environment,
    [Parameter(Mandatory = $true)][string]$BuildStep,
    [Parameter(Mandatory = $true)][string]$StageStep,
    [Parameter(Mandatory = $true)][string]$PublishStep
  )
  $lines = @(Get-Content -LiteralPath $Path)
  $name = Split-Path -Leaf $Path
  Assert-NoExpressionsInRunBlocks -Lines $lines -Name $name

  $environmentIndex = Get-UniqueLineIndex -Lines $lines `
    -Pattern ('^\s{4}environment:\s*' + [Regex]::Escape($Environment) + '\s*$') `
    -Name "$Channel protected environment"
  $resolveIndex = Get-UniqueLineIndex -Lines $lines `
    -Pattern '^\s{6}- name: (Resolve immutable Labs candidate source|Resolve and validate version)\s*$' `
    -Name "$Channel source-resolution step"
  $absenceIndex = Get-UniqueLineIndex -Lines $lines `
    -Pattern '^\s{6}- name: Prove release tag is unused\s*$' `
    -Name "$Channel absence-proof step"
  $restoreIndex = Get-UniqueLineIndex -Lines $lines `
    -Pattern '^\s{6}- name: Restore Android signing identity\s*$' `
    -Name "$Channel signing-identity step"
  $buildIndex = Get-UniqueLineIndex -Lines $lines `
    -Pattern ('^\s{6}- name: ' + [Regex]::Escape($BuildStep) + '\s*$') `
    -Name "$Channel signing-build step"
  $stageIndex = Get-UniqueLineIndex -Lines $lines `
    -Pattern ('^\s{6}- name: ' + [Regex]::Escape($StageStep) + '\s*$') `
    -Name "$Channel identity-stage step"
  $publishIndex = Get-UniqueLineIndex -Lines $lines `
    -Pattern ('^\s{6}- name: ' + [Regex]::Escape($PublishStep) + '\s*$') `
    -Name "$Channel publication step"
  if (-not (
      $environmentIndex -lt $resolveIndex -and
      $resolveIndex -lt $absenceIndex -and
      $absenceIndex -lt $restoreIndex -and
      $restoreIndex -lt $buildIndex -and
      $buildIndex -lt $stageIndex -and
      $stageIndex -lt $publishIndex)) {
    throw "$name no longer resolves and proves source identity before signing and publication."
  }

  $secretLines = @($lines | Where-Object { $_ -match '\$\{\{\s*secrets\.' })
  if ($secretLines.Count -ne 4) {
    throw "$name must expose exactly the four authorized Android signing secrets."
  }
  foreach ($secretLine in $secretLines) {
    if ($secretLine -notmatch
      '^\s{10}(KEYSTORE_BASE64|RUSTY_KIOSK_KEYSTORE_PASSWORD|RUSTY_KIOSK_KEY_ALIAS|RUSTY_KIOSK_KEY_PASSWORD):\s*\$\{\{\s*secrets\.(ANDROID_SIGNING_KEYSTORE_BASE64|ANDROID_SIGNING_KEYSTORE_PASSWORD|ANDROID_SIGNING_KEY_ALIAS|ANDROID_SIGNING_KEY_PASSWORD)\s*\}\}\s*$') {
      throw "$name exposes a signing secret outside the closed environment mapping."
    }
  }
  if ($lines -match '^\s*cache:\s*gradle\s*$') {
    throw "$name must not restore a shared Gradle cache in a signing job."
  }
}

Assert-ReleaseWorkflow `
  -Path (Join-Path $repoRoot '.github\workflows\release-labs.yml') `
  -Channel Labs `
  -Environment android-labs-release `
  -BuildStep 'Test and build the co-installable Labs pair' `
  -StageStep 'Verify identity and stage immutable Labs bundle' `
  -PublishStep 'Publish immutable GitHub prerelease draft, verify, and promote'

Assert-ReleaseWorkflow `
  -Path (Join-Path $repoRoot '.github\workflows\release.yml') `
  -Channel stable `
  -Environment android-stable-release `
  -BuildStep 'Test and build the same-signer release pair' `
  -StageStep 'Verify signing identity and stage public bundle' `
  -PublishStep 'Publish GitHub release'

Write-Output 'Rusty Kiosk release-workflow structure tests passed.'
