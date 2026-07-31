Set-StrictMode -Version Latest

function Resolve-RustyKioskReleaseVersion {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)]
        [string]$Version,

        [ValidateSet('stable', 'labs')]
        [string]$ExpectedChannel
    )

    $match = [regex]::Match(
        $Version,
        '^(0|[1-9]\d{0,3})\.(0|[1-9]\d?)\.(0|[1-9]\d?)(?:-alpha\.([1-9]|[1-8]\d|9[0-8]))?$',
        [Text.RegularExpressions.RegexOptions]::CultureInvariant
    )
    if (-not $match.Success) {
        throw ('Version must be canonical X.Y.Z or X.Y.Z-alpha.N; ' +
            'major <= 2099, minor/patch <= 99, and alpha N must be 1..98.')
    }

    $major = [int64]$match.Groups[1].Value
    $minor = [int64]$match.Groups[2].Value
    $patch = [int64]$match.Groups[3].Value
    if ($major -gt 2099) {
        throw 'Version major must be at most 2099.'
    }

    $productChannel = if ($match.Groups[4].Success) { 'labs' } else { 'stable' }
    $maturity = if ($match.Groups[4].Success) { 'alpha' } else { 'released' }
    if ($ExpectedChannel -and $productChannel -cne $ExpectedChannel) {
        throw "Version $Version belongs to product channel $productChannel, not $ExpectedChannel."
    }

    $alphaOrdinal = if ($maturity -ceq 'alpha') {
        [int64]$match.Groups[4].Value
    } else {
        $null
    }
    $suffix = if ($maturity -ceq 'alpha') { $alphaOrdinal } else { 99L }
    $versionCode = $major * 1000000L + $minor * 10000L + $patch * 100L + $suffix
    if ($versionCode -lt 1 -or $versionCode -gt 2100000000L) {
        throw "Version $Version maps outside the supported Android version-code range."
    }

    [pscustomobject][ordered]@{
        Version = $Version
        Tag = "v$Version"
        Channel = $productChannel
        ProductChannel = $productChannel
        Maturity = $maturity
        DistributionTrack = if ($productChannel -ceq 'labs') { 'github-prerelease' } else { 'github-release' }
        IsPrerelease = $productChannel -ceq 'labs'
        AlphaOrdinal = $alphaOrdinal
        VersionCode = [int]$versionCode
        ExitPolicy = if ($productChannel -ceq 'labs') {
            'uninstall-labs-without-changing-stable'
        } else {
            'stable'
        }
    }
}

Export-ModuleMember -Function Resolve-RustyKioskReleaseVersion
