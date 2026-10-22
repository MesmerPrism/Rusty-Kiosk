[CmdletBinding()]
param(
  [Parameter(Mandatory)][string]$QfmSourceRoot,
  [string]$EvidenceDirectory
)
$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path -LiteralPath (Split-Path -Parent $PSScriptRoot)).Path
$consumerRoot = (Resolve-Path -LiteralPath $QfmSourceRoot).Path
$expectedConsumer = '8b010fa25b5c5d8fbae8d20b4389d587ca7bdef7'
$consumerCommit = (& git -C $consumerRoot rev-parse HEAD).Trim()
if ($LASTEXITCODE -ne 0 -or $consumerCommit -cne $expectedConsumer) {
  throw 'The interoperability gate requires its exact published QFM source pin.'
}
if (@(& git -C $consumerRoot status --porcelain=v1 --untracked-files=all).Count -ne 0) {
  throw 'The external QFM source must be clean; use a task-owned dependency worktree.'
}
if ([string]::IsNullOrWhiteSpace($EvidenceDirectory)) {
  $EvidenceDirectory = Join-Path $repoRoot 'artifacts/catalog-v2-interop/evidence'
}
New-Item -ItemType Directory -Force -Path $EvidenceDirectory | Out-Null
Push-Location $repoRoot
try {
  & .\gradlew.bat --no-daemon :app:testDebugUnitTest :app:writeCatalogV2InteropClasspath
  if ($LASTEXITCODE -ne 0) { throw 'Kotlin producer checks or runtime-classpath export failed.' }
  & dotnet build tools/catalog-v2-interop/Interop.csproj "-p:QfmSourceRoot=$consumerRoot" --nologo
  if ($LASTEXITCODE -ne 0) { throw 'External production consumer interop harness did not build.' }
  $java = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin/java.exe' } else { 'java' }
  & dotnet artifacts/catalog-v2-interop/dotnet/bin/Debug/net10.0/Interop.dll `
    $java (Join-Path $repoRoot 'artifacts/catalog-v2-interop/classpath.txt') $EvidenceDirectory
  if ($LASTEXITCODE -ne 0) { throw 'Actual Kotlin-to-QFM interoperability controls failed.' }
  $producerFiles = Get-ChildItem app/src/main/java/io/github/mesmerprism/rustykiosk -File |
    Where-Object { $_.Name -match '^(DirectOperatorV2|KioskCatalog|AppSigningEvidence|CatalogModels|InstalledAppRepository|TagFileStore|OperatorBridgeService|OperatorBridgeSettings)' }
  $sourceHashes = @($producerFiles | ForEach-Object {
    [ordered]@{ path = [IO.Path]::GetRelativePath($repoRoot, $_.FullName).Replace('\', '/');
      sha256 = (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant() }
  })
  $consumerFiles = Get-ChildItem (Join-Path $consumerRoot 'src/QuestIonAbleFileManager.Core') -File |
    Where-Object { $_.Name -match '^RustyKioskV2' }
  $consumerHashes = @($consumerFiles | ForEach-Object {
    [ordered]@{ path = [IO.Path]::GetRelativePath($consumerRoot, $_.FullName).Replace('\', '/');
      sha256 = (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant() }
  })
  [ordered]@{
    schema = 'rusty.kiosk.catalog_v2_interop_source_receipt.v1'
    producer_commit = (& git rev-parse HEAD).Trim()
    producer_tree = (& git rev-parse 'HEAD^{tree}').Trim()
    producer_dirty = @(& git status --porcelain=v1 --untracked-files=all).Count -ne 0
    producer_sources = $sourceHashes
    consumer_commit = $consumerCommit
    consumer_tree = (& git -C $consumerRoot rev-parse 'HEAD^{tree}').Trim()
    consumer_sources = $consumerHashes
    fixture = 'actual-qfm-subprocess-response.json'
    fixture_sha256 = (Get-FileHash (Join-Path $EvidenceDirectory 'actual-qfm-subprocess-response.json') -Algorithm SHA256).Hash.ToLowerInvariant()
  } | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $EvidenceDirectory 'source-receipt.json') -Encoding utf8NoBOM
} finally { Pop-Location }
