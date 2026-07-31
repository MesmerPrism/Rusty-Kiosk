[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$MainApkPath,

    [Parameter(Mandatory = $true)]
    [string]$SetupHelperApkPath,

    [Parameter(Mandatory = $true)]
    [string]$Version,

    [string]$SourceUrl = 'https://github.com/MesmerPrism/Rusty-Kiosk',
    [string]$SourceRevision = 'working-tree',
    [string]$SourceTree = 'working-tree',
    [ValidateSet('stable', 'alpha')]
    [string]$ExpectedChannel,
    [string]$ExpectedSignerSha256,
    [string]$ApkSignerPath,
    [string]$Aapt2Path,
    [string]$OutputDirectory = (Join-Path $PSScriptRoot '..\artifacts\release-bundle')
)

$ErrorActionPreference = 'Stop'
$repoRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
Import-Module (Join-Path $PSScriptRoot 'RustyKiosk.ReleaseVersion.psm1') -Force
$release = Resolve-RustyKioskReleaseVersion -Version $Version -ExpectedChannel $ExpectedChannel
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
if (-not $Aapt2Path) {
    $sdkRoot = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { $env:ANDROID_SDK_ROOT }
    if ($sdkRoot) {
        $Aapt2Path = (Get-ChildItem -Path (Join-Path $sdkRoot 'build-tools\*\aapt2.exe') -ErrorAction SilentlyContinue |
            Sort-Object FullName -Descending |
            Select-Object -First 1).FullName
    }
}
if (-not $Aapt2Path -or -not (Test-Path -LiteralPath $Aapt2Path -PathType Leaf)) {
    throw 'aapt2 is required to inspect release APK identity.'
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
if ($ExpectedSignerSha256) {
    $normalizedExpectedSigner = $ExpectedSignerSha256.ToLowerInvariant()
    if ($normalizedExpectedSigner -cnotmatch '^[0-9a-f]{64}$') {
        throw 'ExpectedSignerSha256 must be exactly 64 lowercase or uppercase hexadecimal characters.'
    }
    if ($mainCertificate -cne $normalizedExpectedSigner) {
        throw "Observed APK signer $mainCertificate does not match the authorized signer."
    }
}

function Get-ApkIdentity {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][string]$ExpectedPackage
    )
    $output = & $Aapt2Path dump badging $Path 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "APK identity inspection failed for $Path`n$($output -join [Environment]::NewLine)"
    }
    $text = ($output | ForEach-Object { $_.ToString() }) -join [Environment]::NewLine
    $package = [regex]::Match(
        $text,
        "(?m)^package:\s+name='([^']+)'\s+versionCode='(\d+)'\s+versionName='([^']+)'"
    )
    if (-not $package.Success) {
        throw "aapt2 did not report one parseable package identity for $Path."
    }
    $identity = [pscustomobject]@{
        PackageName = $package.Groups[1].Value
        VersionCode = [int64]$package.Groups[2].Value
        VersionName = $package.Groups[3].Value
    }
    if ($identity.PackageName -cne $ExpectedPackage) {
        throw "Expected package $ExpectedPackage, got $($identity.PackageName) in $Path."
    }
    if ($identity.VersionName -cne $release.Version) {
        throw "Expected versionName $($release.Version), got $($identity.VersionName) in $Path."
    }
    if ($identity.VersionCode -ne $release.VersionCode) {
        throw "Expected versionCode $($release.VersionCode), got $($identity.VersionCode) in $Path."
    }
    return $identity
}

$mainIdentity = Get-ApkIdentity -Path $mainApk -ExpectedPackage 'io.github.mesmerprism.rustykiosk'
$helperIdentity =
    Get-ApkIdentity -Path $helperApk -ExpectedPackage 'io.github.mesmerprism.rustykiosk.setuphelper'

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
Source tree: $SourceTree
Version: $Version
Channel: $($release.Channel)
Tag: $($release.Tag)
License: GNU Affero General Public License v3.0 or later (see RUSTY-KIOSK-LICENSE.txt)
"@

$manifest = [ordered]@{
    schema = 'meta.quest.file_manager.rusty_kiosk_bundle.v1'
    build_type = 'release'
    channel = $release.Channel
    prerelease = $release.IsPrerelease
    tag = $release.Tag
    version = $release.Version
    version_code = $release.VersionCode
    identity_mode = 'same-package-in-place'
    exit_policy = $release.ExitPolicy
    source_url = $SourceUrl
    source_revision = $SourceRevision
    source_tree = $SourceTree
    signer_sha256 = $mainCertificate
    files = @(
        [ordered]@{
            name = 'rusty-kiosk.apk'
            package_name = $mainIdentity.PackageName
            version_name = $mainIdentity.VersionName
            version_code = $mainIdentity.VersionCode
            sha256 = (Get-FileHash -LiteralPath $mainOutput -Algorithm SHA256).Hash.ToLowerInvariant()
            bytes = (Get-Item -LiteralPath $mainOutput).Length
        },
        [ordered]@{
            name = 'rusty-kiosk-setup-helper.apk'
            package_name = $helperIdentity.PackageName
            version_name = $helperIdentity.VersionName
            version_code = $helperIdentity.VersionCode
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
