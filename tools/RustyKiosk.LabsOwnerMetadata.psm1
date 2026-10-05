Set-StrictMode -Version Latest

$script:Schema = 'rusty.kiosk.labs_release_owner_metadata.v2'
$script:Repository = 'MesmerPrism/Rusty-Kiosk'
$script:Product = 'rusty-kiosk-labs'
$script:InstallationIdentity = 'io.github.mesmerprism.rustykiosk.labs'
$script:PrimaryArtifactName = 'rusty-kiosk.apk'
$script:SetupHelperName = 'rusty-kiosk-setup-helper.apk'
$script:SetupHelperIdentity = 'io.github.mesmerprism.rustykiosk.setuphelper.labs'
$script:BundleManifestName = 'bundle-manifest.json'
$script:BundleManifestSchema = 'meta.quest.file_manager.rusty_kiosk_bundle.v2'
$script:IdentityMode = 'separate-coinstallable'
$script:SourceUrl = 'https://github.com/MesmerPrism/Rusty-Kiosk'
$script:ExitPolicy = 'uninstall-labs-without-changing-stable'

function Assert-ExactProperties {
    param(
        [Parameter(Mandatory = $true)]$Value,
        [Parameter(Mandatory = $true)][string[]]$Names,
        [Parameter(Mandatory = $true)][string]$Label
    )
    $actual = @($Value.PSObject.Properties.Name | Sort-Object)
    $expected = @($Names | Sort-Object)
    if (($actual -join "`n") -cne ($expected -join "`n")) {
        throw "$Label must contain exactly: $($Names -join ', ')."
    }
}

function Assert-LowercaseSha256 {
    param([Parameter(Mandatory = $true)][string]$Value, [Parameter(Mandatory = $true)][string]$Label)
    if ($Value -cnotmatch '^[0-9a-f]{64}$') {
        throw "$Label must be exactly 64 lowercase hexadecimal characters."
    }
}

function Assert-NoDuplicateJsonProperties {
    param(
        [Parameter(Mandatory = $true)]
        [System.Text.Json.JsonElement]$Element,
        [Parameter(Mandatory = $true)]
        [string]$Label
    )
    if ($Element.ValueKind -eq [System.Text.Json.JsonValueKind]::Object) {
        $names = @($Element.EnumerateObject() | ForEach-Object Name)
        if (@($names | Sort-Object -Unique).Count -ne $names.Count) {
            throw "$Label contains duplicate properties."
        }
        foreach ($property in $Element.EnumerateObject()) {
            Assert-NoDuplicateJsonProperties `
                -Element $property.Value `
                -Label "$Label.$($property.Name)"
        }
    } elseif ($Element.ValueKind -eq [System.Text.Json.JsonValueKind]::Array) {
        $index = 0
        foreach ($item in $Element.EnumerateArray()) {
            Assert-NoDuplicateJsonProperties `
                -Element $item `
                -Label "$Label[$index]"
            $index++
        }
    }
}

function Read-StrictJsonObject {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][string]$Label
    )
    $raw = Get-Content -Raw -LiteralPath $Path -ErrorAction Stop
    try {
        $json = [System.Text.Json.JsonDocument]::Parse($raw)
    } catch {
        throw "$Label is not valid JSON: $($_.Exception.Message)"
    }
    try {
        if ($json.RootElement.ValueKind -ne [System.Text.Json.JsonValueKind]::Object) {
            throw "$Label root must be an object."
        }
        Assert-NoDuplicateJsonProperties -Element $json.RootElement -Label $Label
    } finally {
        $json.Dispose()
    }
    return $raw | ConvertFrom-Json
}

function New-RustyKioskLabsOwnerMetadata {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)]$Release,
        [Parameter(Mandatory = $true)][string]$SourceRevision,
        [Parameter(Mandatory = $true)][string]$SourceTree,
        [Parameter(Mandatory = $true)][string]$PrimaryArtifactPath,
        [Parameter(Mandatory = $true)][string]$BundleManifestPath
    )
    if ($Release.ProductChannel -cne 'labs' -or
        $Release.Maturity -cne 'alpha' -or
        $Release.IsPrerelease -ne $true) {
        throw 'Kiosk Labs owner metadata requires the Labs product channel and alpha maturity.'
    }
    $artifact = Get-Item -LiteralPath $PrimaryArtifactPath -ErrorAction Stop
    if ($artifact.Length -le 0) {
        throw 'The complete-product APK must have a positive byte count.'
    }
    $manifestFile = Get-Item -LiteralPath $BundleManifestPath -ErrorAction Stop
    if ($manifestFile.Name -cne $script:BundleManifestName -or
        $manifestFile.Length -le 0) {
        throw 'The exact nonempty Kiosk bundle manifest is required.'
    }
    $manifest = Read-StrictJsonObject `
        -Path $manifestFile.FullName `
        -Label 'Kiosk bundle manifest'
    $primaryEntries = @(
        $manifest.files | Where-Object name -CEQ $script:PrimaryArtifactName
    )
    if ($manifest.schema -cne $script:BundleManifestSchema -or
        $primaryEntries.Count -ne 1) {
        throw 'The owner metadata input is not the exact Kiosk bundle manifest.'
    }
    return [ordered]@{
        schema = $script:Schema
        repository = $script:Repository
        product = $script:Product
        product_channel = 'labs'
        maturity = 'alpha'
        distribution_track = 'github-prerelease'
        prerelease = $true
        tag = $Release.Tag
        version = $Release.Version
        source_revision = $SourceRevision
        source_tree = $SourceTree
        installation_identity = $script:InstallationIdentity
        coinstallable_lineage = [ordered]@{
            identity_mode = $manifest.identity_mode
            package_name = $primaryEntries[0].package_name
            signer_sha256 = $manifest.signer_sha256
            version_name = $primaryEntries[0].version_name
            version_code = $primaryEntries[0].version_code
            exit_policy = $manifest.exit_policy
        }
        bundle_manifest = [ordered]@{
            schema = $script:BundleManifestSchema
            name = $script:BundleManifestName
            sha256 = (
                Get-FileHash -LiteralPath $manifestFile.FullName -Algorithm SHA256
            ).Hash.ToLowerInvariant()
            bytes = $manifestFile.Length
        }
        primary_artifact = [ordered]@{
            role = 'complete-product'
            name = $script:PrimaryArtifactName
            sha256 = (Get-FileHash -LiteralPath $artifact.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
            bytes = $artifact.Length
        }
    }
}

function Assert-RustyKioskLabsOwnerMetadata {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)][string]$MetadataPath,
        [Parameter(Mandatory = $true)][string]$ExpectedTag,
        [Parameter(Mandatory = $true)][string]$ExpectedVersion,
        [Parameter(Mandatory = $true)][string]$ExpectedSourceRevision,
        [Parameter(Mandatory = $true)][string]$ExpectedSourceTree,
        [Parameter(Mandatory = $true)][string]$PrimaryArtifactPath,
        [Parameter(Mandatory = $true)][string]$BundleManifestPath
    )
    $metadata = Read-StrictJsonObject `
        -Path $MetadataPath `
        -Label 'Kiosk Labs owner metadata'
    Assert-ExactProperties $metadata @(
        'schema', 'repository', 'product', 'product_channel', 'maturity',
        'distribution_track', 'prerelease', 'tag', 'version',
        'source_revision', 'source_tree', 'installation_identity',
        'coinstallable_lineage', 'bundle_manifest', 'primary_artifact'
    ) 'Kiosk Labs owner metadata'
    Assert-ExactProperties $metadata.coinstallable_lineage @(
        'identity_mode', 'package_name', 'signer_sha256', 'version_name',
        'version_code', 'exit_policy'
    ) 'coinstallable_lineage'
    Assert-ExactProperties $metadata.bundle_manifest @(
        'schema', 'name', 'sha256', 'bytes'
    ) 'bundle_manifest'
    Assert-ExactProperties $metadata.primary_artifact @('role', 'name', 'sha256', 'bytes') 'primary_artifact'

    $expected = [ordered]@{
        schema = $script:Schema
        repository = $script:Repository
        product = $script:Product
        product_channel = 'labs'
        maturity = 'alpha'
        distribution_track = 'github-prerelease'
        prerelease = $true
        tag = $ExpectedTag
        version = $ExpectedVersion
        source_revision = $ExpectedSourceRevision
        source_tree = $ExpectedSourceTree
        installation_identity = $script:InstallationIdentity
    }
    foreach ($entry in $expected.GetEnumerator()) {
        if ($metadata.($entry.Key) -cne $entry.Value) {
            throw "Kiosk Labs owner metadata $($entry.Key) does not match its owner-authorized value."
        }
    }
    if ($ExpectedSourceRevision -cnotmatch '^[0-9a-f]{40}$' -or
        $ExpectedSourceTree -cnotmatch '^[0-9a-f]{40}$') {
        throw 'Expected source revision and tree must be exact lowercase Git object IDs.'
    }
    $versionMatch = [regex]::Match(
        $ExpectedVersion,
        '^(0|[1-9]\d{0,3})\.(0|[1-9]\d?)\.(0|[1-9]\d?)-alpha\.([1-9]|[1-8]\d|9[0-8])$',
        [Text.RegularExpressions.RegexOptions]::CultureInvariant
    )
    if ($ExpectedTag -cne "v$ExpectedVersion" -or
        -not $versionMatch.Success -or
        [int64]$versionMatch.Groups[1].Value -gt 2099) {
        throw 'Expected tag and version are not one exact canonical alpha tuple.'
    }
    $expectedVersionCode =
        [int64]$versionMatch.Groups[1].Value * 1000000L +
        [int64]$versionMatch.Groups[2].Value * 10000L +
        [int64]$versionMatch.Groups[3].Value * 100L +
        [int64]$versionMatch.Groups[4].Value
    if ($metadata.primary_artifact.role -cne 'complete-product' -or
        $metadata.primary_artifact.name -cne $script:PrimaryArtifactName) {
        throw 'primary_artifact must explicitly name rusty-kiosk.apk as the complete product.'
    }
    Assert-LowercaseSha256 $metadata.primary_artifact.sha256 'primary_artifact.sha256'
    if ($metadata.primary_artifact.bytes -isnot [long] -and
        $metadata.primary_artifact.bytes -isnot [int]) {
        throw 'primary_artifact.bytes must be an integer.'
    }
    if ([int64]$metadata.primary_artifact.bytes -le 0) {
        throw 'primary_artifact.bytes must be positive.'
    }
    Assert-LowercaseSha256 `
        $metadata.coinstallable_lineage.signer_sha256 `
        'coinstallable_lineage.signer_sha256'
    if ($metadata.coinstallable_lineage.identity_mode -cne $script:IdentityMode -or
        $metadata.coinstallable_lineage.package_name -cne $script:InstallationIdentity -or
        $metadata.coinstallable_lineage.version_name -cne $ExpectedVersion -or
        [int64]$metadata.coinstallable_lineage.version_code -ne $expectedVersionCode -or
        $metadata.coinstallable_lineage.exit_policy -cne $script:ExitPolicy) {
        throw 'coinstallable_lineage is not the exact side-by-side Labs contract.'
    }
    Assert-LowercaseSha256 `
        $metadata.bundle_manifest.sha256 `
        'bundle_manifest.sha256'
    if ($metadata.bundle_manifest.schema -cne $script:BundleManifestSchema -or
        $metadata.bundle_manifest.name -cne $script:BundleManifestName -or
        ($metadata.bundle_manifest.bytes -isnot [long] -and
            $metadata.bundle_manifest.bytes -isnot [int]) -or
        [int64]$metadata.bundle_manifest.bytes -le 0) {
        throw 'bundle_manifest is not the exact owner manifest commitment.'
    }

    $artifact = Get-Item -LiteralPath $PrimaryArtifactPath -ErrorAction Stop
    $artifactHash = (Get-FileHash -LiteralPath $artifact.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($artifact.Name -cne $script:PrimaryArtifactName -or
        $metadata.primary_artifact.sha256 -cne $artifactHash -or
        [int64]$metadata.primary_artifact.bytes -ne $artifact.Length) {
        throw 'primary_artifact does not match the actual complete-product APK.'
    }

    $bundleFile = Get-Item -LiteralPath $BundleManifestPath -ErrorAction Stop
    $bundleHash = (
        Get-FileHash -LiteralPath $bundleFile.FullName -Algorithm SHA256
    ).Hash.ToLowerInvariant()
    if ($bundleFile.Name -cne $script:BundleManifestName -or
        $metadata.bundle_manifest.sha256 -cne $bundleHash -or
        [int64]$metadata.bundle_manifest.bytes -ne $bundleFile.Length) {
        throw 'bundle_manifest does not bind the exact published manifest bytes.'
    }
    $bundle = Read-StrictJsonObject `
        -Path $bundleFile.FullName `
        -Label 'Kiosk bundle manifest'
    Assert-ExactProperties $bundle @(
        'schema', 'build_type', 'product_channel', 'maturity',
        'distribution_track', 'prerelease', 'tag', 'version',
        'version_code', 'identity_mode', 'exit_policy', 'source_url',
        'source_revision', 'source_tree', 'signer_sha256', 'files'
    ) 'Kiosk bundle manifest'
    if (-not ($bundle.files -is [array]) -or @($bundle.files).Count -ne 4) {
        throw 'Kiosk bundle manifest must contain exactly four payload entries.'
    }
    $expectedFileNames = @(
        'RUSTY-KIOSK-LICENSE.txt',
        'RUSTY-KIOSK-SOURCE.txt',
        $script:SetupHelperName,
        $script:PrimaryArtifactName
    ) | Sort-Object
    $actualFileNames = @($bundle.files.name | Sort-Object)
    if (($actualFileNames -join "`n") -cne ($expectedFileNames -join "`n")) {
        throw 'Kiosk bundle manifest payload inventory is not exact.'
    }
    foreach ($entry in @($bundle.files)) {
        $expectedProperties = if ($entry.name -like '*.apk') {
            @('name', 'package_name', 'version_name', 'version_code', 'sha256', 'bytes')
        } else {
            @('name', 'sha256', 'bytes')
        }
        Assert-ExactProperties $entry $expectedProperties "bundle file $($entry.name)"
        Assert-LowercaseSha256 $entry.sha256 "bundle file $($entry.name) SHA-256"
        if (($entry.bytes -isnot [long] -and $entry.bytes -isnot [int]) -or
            [int64]$entry.bytes -le 0) {
            throw "Bundle file $($entry.name) must have a positive integer byte count."
        }
    }
    $bundleEntries = @(
        $bundle.files | Where-Object name -CEQ $script:PrimaryArtifactName
    )
    $helperEntries = @(
        $bundle.files | Where-Object name -CEQ $script:SetupHelperName
    )
    Assert-LowercaseSha256 $bundle.signer_sha256 'bundle signer_sha256'
    if ($bundle.schema -cne $script:BundleManifestSchema -or
        $bundle.build_type -cne 'release' -or
        $bundle.product_channel -cne 'labs' -or
        $bundle.maturity -cne 'alpha' -or
        $bundle.distribution_track -cne 'github-prerelease' -or
        $bundle.prerelease -ne $true -or
        $bundle.tag -cne $ExpectedTag -or
        $bundle.version -cne $ExpectedVersion -or
        [int64]$bundle.version_code -ne $expectedVersionCode -or
        $bundle.identity_mode -cne $script:IdentityMode -or
        $bundle.exit_policy -cne $script:ExitPolicy -or
        $bundle.source_url -cne $script:SourceUrl -or
        $bundle.source_revision -cne $ExpectedSourceRevision -or
        $bundle.source_tree -cne $ExpectedSourceTree -or
        $bundle.signer_sha256 -cne $metadata.coinstallable_lineage.signer_sha256 -or
        $bundleEntries.Count -ne 1 -or
        $helperEntries.Count -ne 1 -or
        $bundleEntries[0].package_name -cne $script:InstallationIdentity -or
        $bundleEntries[0].version_name -cne $ExpectedVersion -or
        [int64]$bundleEntries[0].version_code -ne $expectedVersionCode -or
        $bundleEntries[0].sha256 -cne $artifactHash -or
        [int64]$bundleEntries[0].bytes -ne $artifact.Length -or
        $helperEntries[0].package_name -cne $script:SetupHelperIdentity -or
        $helperEntries[0].version_name -cne $ExpectedVersion -or
        [int64]$helperEntries[0].version_code -ne $expectedVersionCode) {
        throw 'Kiosk Labs owner metadata does not match the owner build/bundle evidence.'
    }
}

Export-ModuleMember -Function New-RustyKioskLabsOwnerMetadata, Assert-RustyKioskLabsOwnerMetadata
