[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$MainApkPath,

    [Parameter(Mandatory = $true)]
    [string]$SetupHelperApkPath,

    [Parameter(Mandatory = $true)]
    [ValidatePattern('^\d+\.\d+\.\d+$')]
    [string]$Version,

    [string]$SourceUrl = 'https://github.com/MesmerPrism/Rusty-Kiosk',
    [string]$SourceRevision = 'working-tree',
    [string]$ApkSignerPath,
    [string]$OutputDirectory = (Join-Path $PSScriptRoot '..\artifacts\release-bundle')
)

$ErrorActionPreference = 'Stop'
$repoRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$artifactsRoot = [IO.Path]::GetFullPath((Join-Path $repoRoot 'artifacts'))
$OutputDirectory = [IO.Path]::GetFullPath($OutputDirectory)
if (-not $OutputDirectory.StartsWith($artifactsRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
    throw "Release bundle output must stay under $artifactsRoot."
}

$mainApk = [IO.Path]::GetFullPath($MainApkPath)
$helperApk = [IO.Path]::GetFullPath($SetupHelperApkPath)
$license = Join-Path $repoRoot 'LICENSE'
foreach ($path in @($mainApk, $helperApk, $license)) {
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
        throw "Required release input was not found: $path"
    }
}
if ([IO.Path]::GetExtension($mainApk) -ne '.apk' -or [IO.Path]::GetExtension($helperApk) -ne '.apk') {
    throw 'Both application inputs must be APK files.'
}

if (-not $ApkSignerPath) {
    $sdkRoot = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { $env:ANDROID_SDK_ROOT }
    if ($sdkRoot) {
        $ApkSignerPath = (Get-ChildItem -Path (Join-Path $sdkRoot 'build-tools\*\apksigner.bat') -ErrorAction SilentlyContinue |
            Sort-Object FullName -Descending |
            Select-Object -First 1).FullName
    }
}
if (-not $ApkSignerPath -or -not (Test-Path -LiteralPath $ApkSignerPath -PathType Leaf)) {
    throw 'apksigner is required to verify the release bundle.'
}

function Get-ApkCertificateDigest {
    param([Parameter(Mandatory = $true)][string]$Path)
    $output = & $ApkSignerPath verify --print-certs $Path 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "APK signature verification failed for $Path`n$($output -join [Environment]::NewLine)"
    }
    $text = ($output | ForEach-Object { $_.ToString() }) -join [Environment]::NewLine
    $matches = [regex]::Matches(
        $text,
        '(?im)certificate\s+SHA-?256\s+digest\s*:\s*([0-9a-fA-F:\- ]{64,128})'
    )
    if ($matches.Count -eq 0) {
        throw "No signing certificate digest was found for $Path.`nVerifier output:`n$text"
    }
    $digests = @($matches | ForEach-Object {
        ($_.Groups[1].Value -replace '[^0-9a-fA-F]', '').ToLowerInvariant()
    } | Sort-Object -Unique)
    if ($digests.Count -ne 1 -or $digests[0].Length -ne 64) {
        throw "Expected exactly one 32-byte signing certificate digest for $Path, got: $($digests -join ', ')"
    }
    return $digests[0]
}

$mainCertificate = Get-ApkCertificateDigest -Path $mainApk
$helperCertificate = Get-ApkCertificateDigest -Path $helperApk
if ($mainCertificate -ne $helperCertificate) {
    throw 'The main and setup-helper APKs must use the same signing certificate.'
}

if (Test-Path -LiteralPath $OutputDirectory) {
    Remove-Item -LiteralPath $OutputDirectory -Recurse -Force
}
New-Item -ItemType Directory -Path $OutputDirectory -Force | Out-Null

$mainOutput = Join-Path $OutputDirectory 'rusty-kiosk.apk'
$helperOutput = Join-Path $OutputDirectory 'rusty-kiosk-setup-helper.apk'
Copy-Item -LiteralPath $mainApk -Destination $mainOutput
Copy-Item -LiteralPath $helperApk -Destination $helperOutput
$licenseOutput = Join-Path $OutputDirectory 'RUSTY-KIOSK-LICENSE.txt'
$sourceOutput = Join-Path $OutputDirectory 'RUSTY-KIOSK-SOURCE.txt'
Copy-Item -LiteralPath $license -Destination $licenseOutput
Set-Content -LiteralPath $sourceOutput -Encoding utf8 -Value @"
Rusty Kiosk source: $SourceUrl
Source revision: $SourceRevision
Version: $Version
License: GNU Affero General Public License v3.0 or later (see RUSTY-KIOSK-LICENSE.txt)
"@

$manifest = [ordered]@{
    schema = 'meta.quest.file_manager.rusty_kiosk_bundle.v1'
    build_type = 'release'
    version = $Version
    source_url = $SourceUrl
    source_revision = $SourceRevision
    signer_sha256 = $mainCertificate
    staged_at_utc = [DateTimeOffset]::UtcNow.ToString('O')
    files = @(
        [ordered]@{
            name = 'rusty-kiosk.apk'
            sha256 = (Get-FileHash -LiteralPath $mainOutput -Algorithm SHA256).Hash.ToLowerInvariant()
            bytes = (Get-Item -LiteralPath $mainOutput).Length
        },
        [ordered]@{
            name = 'rusty-kiosk-setup-helper.apk'
            sha256 = (Get-FileHash -LiteralPath $helperOutput -Algorithm SHA256).Hash.ToLowerInvariant()
            bytes = (Get-Item -LiteralPath $helperOutput).Length
        },
        [ordered]@{
            name = 'RUSTY-KIOSK-LICENSE.txt'
            sha256 = (Get-FileHash -LiteralPath $licenseOutput -Algorithm SHA256).Hash.ToLowerInvariant()
            bytes = (Get-Item -LiteralPath $licenseOutput).Length
        },
        [ordered]@{
            name = 'RUSTY-KIOSK-SOURCE.txt'
            sha256 = (Get-FileHash -LiteralPath $sourceOutput -Algorithm SHA256).Hash.ToLowerInvariant()
            bytes = (Get-Item -LiteralPath $sourceOutput).Length
        }
    )
}
$manifest | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $OutputDirectory 'bundle-manifest.json') -Encoding utf8

Get-ChildItem -LiteralPath $OutputDirectory -File | Sort-Object Name | Select-Object Name, Length, FullName
