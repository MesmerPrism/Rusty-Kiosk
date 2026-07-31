[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$repoRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
Import-Module (Join-Path $PSScriptRoot 'RustyKiosk.ReleaseVersion.psm1') -Force
Import-Module (Join-Path $PSScriptRoot 'RustyKiosk.AlphaOwnerMetadata.psm1') -Force
$artifactsRoot = [IO.Path]::GetFullPath((Join-Path $repoRoot 'artifacts'))
New-Item -ItemType Directory -Path $artifactsRoot -Force | Out-Null
$runDirectory = [IO.Path]::GetFullPath(
    (Join-Path $artifactsRoot ("release-pipeline-test-{0}" -f [guid]::NewGuid().ToString('N')))
)
if (-not $runDirectory.StartsWith(
        $artifactsRoot + [IO.Path]::DirectorySeparatorChar,
        [StringComparison]::OrdinalIgnoreCase)) {
    throw 'The release pipeline test directory escaped the repository artifacts root.'
}
New-Item -ItemType Directory -Path $runDirectory | Out-Null

$testPassword = 'local-release-pipeline-test'
$keystore = Join-Path $runDirectory 'test-release.jks'
$bundle = Join-Path $runDirectory 'bundle'
$repeatBundle = Join-Path $runDirectory 'repeat-bundle'
$nativeOutputBundle = Join-Path $runDirectory 'native-output-bundle'
$nativeOutputProxy = Join-Path $runDirectory 'apksigner-stderr-proxy.cmd'
$v2LabelBundle = Join-Path $runDirectory 'v2-label-bundle'
$v2LabelProxy = Join-Path $runDirectory 'apksigner-v2-label-proxy.cmd'
$stableBundle = Join-Path $runDirectory 'stable-bundle'
$mainApk = Join-Path $repoRoot 'app\build\outputs\apk\release\app-release.apk'
$helperApk = Join-Path $repoRoot 'setup-helper\build\outputs\apk\release\setup-helper-release.apk'
$testSourceRevision = '1111111111111111111111111111111111111111'
$testSourceTree = '2222222222222222222222222222222222222222'

try {
    $alpha = Resolve-RustyKioskReleaseVersion -Version '0.6.6-alpha.1' -ExpectedChannel alpha
    if ($alpha.VersionCode -ne 60601 -or
        $alpha.Tag -cne 'v0.6.6-alpha.1' -or
        -not $alpha.IsPrerelease) {
        throw 'The canonical alpha version did not resolve to its expected tag and version code.'
    }
    $stable = Resolve-RustyKioskReleaseVersion -Version '0.6.6' -ExpectedChannel stable
    if ($stable.VersionCode -ne 60699 -or $stable.IsPrerelease) {
        throw 'The canonical stable version did not reserve suffix 99.'
    }
    $upperBoundary = Resolve-RustyKioskReleaseVersion -Version '2099.99.99-alpha.98'
    if ($upperBoundary.VersionCode -ne 2099999998) {
        throw 'The upper supported alpha boundary mapped incorrectly.'
    }
    foreach ($invalidVersion in @(
        '0.6.6-alpha.0',
        '0.6.6-alpha.00',
        '0.6.6-alpha.01',
        '0.6.6-alpha.99',
        '0.6.6-alpha.100',
        '0.6.6-ALPHA.1',
        '0.6.6-beta.1',
        '0.6.6-alpha.1+build',
        '0.100.0-alpha.1',
        '0.6.100-alpha.1',
        '2100.0.0-alpha.1'
    )) {
        $rejected = $false
        try {
            Resolve-RustyKioskReleaseVersion -Version $invalidVersion | Out-Null
        } catch {
            $rejected = $true
        }
        if (-not $rejected) {
            throw "Invalid release version was accepted: $invalidVersion"
        }
    }

    $appBuild = Get-Content -Raw -LiteralPath (Join-Path $repoRoot 'app\build.gradle.kts')
    $helperBuild = Get-Content -Raw -LiteralPath (Join-Path $repoRoot 'setup-helper\build.gradle.kts')
    foreach ($expectation in @(
        @{ Text = $appBuild; Pattern = 'requestedReleaseVersion \?: "0.6.5"'; Name = 'app stable versionName' },
        @{ Text = $appBuild; Pattern = '(?s)requestedReleaseVersion == null\).*?\b15\b'; Name = 'app stable versionCode' },
        @{ Text = $helperBuild; Pattern = 'requestedReleaseVersion \?: "0.5.0"'; Name = 'helper stable versionName' },
        @{ Text = $helperBuild; Pattern = '(?s)requestedReleaseVersion == null\).*?\b5\b'; Name = 'helper stable versionCode' }
    )) {
        if ($expectation.Text -notmatch $expectation.Pattern) {
            throw "The unchanged $($expectation.Name) default is missing."
        }
    }

    $keytool = (Get-Command keytool -ErrorAction Stop).Source
    & $keytool -genkeypair `
        -keystore $keystore `
        -storepass $testPassword `
        -keypass $testPassword `
        -alias release-test `
        -keyalg RSA `
        -keysize 2048 `
        -validity 1 `
        -dname 'CN=Local Release Pipeline Test' `
        -noprompt
    if ($LASTEXITCODE -ne 0) {
        throw 'Temporary signing-key generation failed.'
    }

    $env:RUSTY_KIOSK_KEYSTORE_PATH = $keystore
    $env:RUSTY_KIOSK_KEYSTORE_PASSWORD = $testPassword
    $env:RUSTY_KIOSK_KEY_ALIAS = 'release-test'
    $env:RUSTY_KIOSK_KEY_PASSWORD = $testPassword
    & (Join-Path $repoRoot 'gradlew.bat') `
        '-PrustyKioskReleaseVersion=0.6.6-alpha.1' `
        :app:assembleRelease `
        :setup-helper:assembleRelease
    if ($LASTEXITCODE -ne 0) {
        throw 'The local signed alpha release build failed.'
    }

    & (Join-Path $PSScriptRoot 'Stage-ReleaseBundle.ps1') `
        -MainApkPath $mainApk `
        -SetupHelperApkPath $helperApk `
        -Version '0.6.6-alpha.1' `
        -ExpectedChannel alpha `
        -SourceRevision $testSourceRevision `
        -SourceTree $testSourceTree `
        -OutputDirectory $bundle
    if ($LASTEXITCODE -ne 0) {
        throw 'The alpha release bundle staging test failed.'
    }

    $manifest =
        Get-Content -Raw -LiteralPath (Join-Path $bundle 'bundle-manifest.json') |
            ConvertFrom-Json
    if ($manifest.schema -cne 'meta.quest.file_manager.rusty_kiosk_bundle.v1' -or
        $manifest.build_type -cne 'release' -or
        $manifest.channel -cne 'alpha' -or
        $manifest.prerelease -ne $true -or
        $manifest.tag -cne 'v0.6.6-alpha.1' -or
        $manifest.version -cne '0.6.6-alpha.1' -or
        $manifest.version_code -ne 60601 -or
        $manifest.identity_mode -cne 'same-package-in-place' -or
        $manifest.source_revision -cne $testSourceRevision -or
        $manifest.source_tree -cne $testSourceTree -or
        [string]::IsNullOrWhiteSpace($manifest.signer_sha256) -or
        @($manifest.files).Count -ne 4) {
        throw 'The staged release manifest did not record the expected alpha identity and complete file set.'
    }

    $mainEntry = @($manifest.files | Where-Object name -eq 'rusty-kiosk.apk')
    $helperEntry = @($manifest.files | Where-Object name -eq 'rusty-kiosk-setup-helper.apk')
    if ($mainEntry.Count -ne 1 -or
        $mainEntry[0].package_name -cne 'io.github.mesmerprism.rustykiosk' -or
        $mainEntry[0].version_name -cne '0.6.6-alpha.1' -or
        $mainEntry[0].version_code -ne 60601 -or
        $helperEntry.Count -ne 1 -or
        $helperEntry[0].package_name -cne 'io.github.mesmerprism.rustykiosk.setuphelper' -or
        $helperEntry[0].version_name -cne '0.6.6-alpha.1' -or
        $helperEntry[0].version_code -ne 60601) {
        throw 'The staged APK identities did not match the alpha release tuple.'
    }
    foreach ($file in @($manifest.files)) {
        $path = Join-Path $bundle $file.name
        if (-not (Test-Path -LiteralPath $path -PathType Leaf) -or
            (Get-Item -LiteralPath $path).Length -ne $file.bytes -or
            (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant() -cne
                $file.sha256) {
            throw "The staged release file does not match its manifest entry: $($file.name)"
        }
    }

    $ownerMetadataPath = Join-Path $bundle 'rusty-kiosk-alpha-owner-release.json'
    $ownerValidationArguments = @{
        MetadataPath = $ownerMetadataPath
        ExpectedTag = 'v0.6.6-alpha.1'
        ExpectedVersion = '0.6.6-alpha.1'
        ExpectedSourceRevision = $testSourceRevision
        ExpectedSourceTree = $testSourceTree
        PrimaryArtifactPath = Join-Path $bundle 'rusty-kiosk.apk'
        BundleManifestPath = Join-Path $bundle 'bundle-manifest.json'
    }
    & (Join-Path $PSScriptRoot 'Test-KioskAlphaOwnerMetadata.ps1') @ownerValidationArguments | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw 'The owner-generated alpha metadata did not pass its dedicated validator.'
    }
    $ownerMetadata = Get-Content -Raw -LiteralPath $ownerMetadataPath | ConvertFrom-Json
    $ownerExpected = @{
        schema = 'rusty.kiosk.alpha_release_owner_metadata.v1'
        repository = 'MesmerPrism/Rusty-Kiosk'
        product = 'rusty-kiosk'
        channel = 'alpha'
        prerelease = $true
        tag = 'v0.6.6-alpha.1'
        version = '0.6.6-alpha.1'
        source_revision = $testSourceRevision
        source_tree = $testSourceTree
        installation_identity = 'io.github.mesmerprism.rustykiosk'
    }
    foreach ($entry in $ownerExpected.GetEnumerator()) {
        if ($ownerMetadata.($entry.Key) -cne $entry.Value) {
            throw "Alpha owner metadata has the wrong $($entry.Key)."
        }
    }
    if ($ownerMetadata.primary_artifact.role -cne 'complete-product' -or
        $ownerMetadata.primary_artifact.name -cne 'rusty-kiosk.apk' -or
        $ownerMetadata.primary_artifact.sha256 -cnotmatch '^[0-9a-f]{64}$' -or
        [int64]$ownerMetadata.primary_artifact.bytes -le 0) {
        throw 'Alpha owner metadata did not explicitly bind the complete-product APK.'
    }
    $bundleManifestPath = Join-Path $bundle 'bundle-manifest.json'
    $bundleManifestFile = Get-Item -LiteralPath $bundleManifestPath
    $bundleManifestHash = (
        Get-FileHash -LiteralPath $bundleManifestPath -Algorithm SHA256
    ).Hash.ToLowerInvariant()
    if ($ownerMetadata.same_package_lineage.identity_mode -cne
            'same-package-in-place' -or
        $ownerMetadata.same_package_lineage.package_name -cne
            'io.github.mesmerprism.rustykiosk' -or
        $ownerMetadata.same_package_lineage.signer_sha256 -cne
            $manifest.signer_sha256 -or
        $ownerMetadata.same_package_lineage.version_name -cne
            '0.6.6-alpha.1' -or
        $ownerMetadata.same_package_lineage.version_code -ne 60601 -or
        $ownerMetadata.same_package_lineage.exit_policy -cne
            'in-place; install a later same-signer stable build with a higher versionCode' -or
        $ownerMetadata.bundle_manifest.schema -cne
            'meta.quest.file_manager.rusty_kiosk_bundle.v1' -or
        $ownerMetadata.bundle_manifest.name -cne 'bundle-manifest.json' -or
        $ownerMetadata.bundle_manifest.sha256 -cne $bundleManifestHash -or
        [int64]$ownerMetadata.bundle_manifest.bytes -ne $bundleManifestFile.Length) {
        throw 'Alpha owner metadata did not bind the manifest and exact same-package lineage.'
    }

    $damageDirectory = Join-Path $runDirectory 'owner-metadata-damage'
    New-Item -ItemType Directory -Path $damageDirectory | Out-Null
    $wrongCases = @(
        @{ Path = 'schema'; Value = 'foreign.schema.v1' },
        @{ Path = 'repository'; Value = 'Other/Repository' },
        @{ Path = 'product'; Value = 'Other Product' },
        @{ Path = 'channel'; Value = 'stable' },
        @{ Path = 'prerelease'; Value = $false },
        @{ Path = 'tag'; Value = 'v0.6.6-alpha.2' },
        @{ Path = 'version'; Value = '0.6.6-alpha.2' },
        @{ Path = 'source_revision'; Value = ('3' * 40) },
        @{ Path = 'source_tree'; Value = ('4' * 40) },
        @{ Path = 'installation_identity'; Value = 'io.example.other' },
        @{ Path = 'same_package_lineage.identity_mode'; Value = 'side-by-side' },
        @{ Path = 'same_package_lineage.package_name'; Value = 'io.example.other' },
        @{ Path = 'same_package_lineage.signer_sha256'; Value = ('A' * 64) },
        @{ Path = 'same_package_lineage.version_name'; Value = '0.6.6-alpha.2' },
        @{ Path = 'same_package_lineage.version_code'; Value = 60602 },
        @{ Path = 'same_package_lineage.exit_policy'; Value = 'downgrade' },
        @{ Path = 'bundle_manifest.schema'; Value = 'foreign.bundle.v1' },
        @{ Path = 'bundle_manifest.name'; Value = 'foreign.json' },
        @{ Path = 'bundle_manifest.sha256'; Value = ('A' * 64) },
        @{ Path = 'bundle_manifest.bytes'; Value = 0 },
        @{ Path = 'primary_artifact.role'; Value = 'setup-helper' },
        @{ Path = 'primary_artifact.name'; Value = 'rusty-kiosk-setup-helper.apk' },
        @{ Path = 'primary_artifact.sha256'; Value = ('A' * 64) },
        @{ Path = 'primary_artifact.bytes'; Value = 0 }
    )
    $allPaths = @($wrongCases.Path)
    $damageCases = @()
    foreach ($case in $wrongCases) {
        $damageCases += @{ Kind = 'wrong'; Path = $case.Path; Value = $case.Value }
    }
    foreach ($path in $allPaths) {
        $damageCases += @{ Kind = 'missing'; Path = $path }
    }
    foreach ($path in @(
        'same_package_lineage',
        'bundle_manifest',
        'primary_artifact'
    )) {
        $damageCases += @{ Kind = 'missing'; Path = $path }
    }
    $damageCases += @{ Kind = 'expanded'; Path = 'unexpected_owner_field'; Value = 'damage' }
    $damageCases += @{
        Kind = 'expanded'
        Path = 'same_package_lineage.unexpected_lineage_field'
        Value = 'damage'
    }
    $damageCases += @{
        Kind = 'expanded'
        Path = 'bundle_manifest.unexpected_manifest_field'
        Value = 'damage'
    }
    $damageCases += @{ Kind = 'expanded'; Path = 'primary_artifact.unexpected_artifact_field'; Value = 'damage' }
    $damageIndex = 0
    foreach ($damage in $damageCases) {
        $damaged = Get-Content -Raw -LiteralPath $ownerMetadataPath | ConvertFrom-Json
        $segments = @($damage.Path -split '\.')
        $target = $damaged
        for ($i = 0; $i -lt $segments.Count - 1; $i++) {
            $target = $target.($segments[$i])
        }
        $leaf = $segments[-1]
        if ($damage.Kind -ceq 'missing') {
            $target.PSObject.Properties.Remove($leaf)
        } elseif ($damage.Kind -ceq 'expanded') {
            $target | Add-Member -NotePropertyName $leaf -NotePropertyValue $damage.Value
        } else {
            $target.($leaf) = $damage.Value
        }
        $damagePath = Join-Path $damageDirectory ("damage-{0:D2}.json" -f $damageIndex++)
        $damaged | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $damagePath -Encoding utf8
        $rejected = $false
        try {
            Assert-RustyKioskAlphaOwnerMetadata `
                -MetadataPath $damagePath `
                -ExpectedTag $ownerValidationArguments.ExpectedTag `
                -ExpectedVersion $ownerValidationArguments.ExpectedVersion `
                -ExpectedSourceRevision $ownerValidationArguments.ExpectedSourceRevision `
                -ExpectedSourceTree $ownerValidationArguments.ExpectedSourceTree `
                -PrimaryArtifactPath $ownerValidationArguments.PrimaryArtifactPath `
                -BundleManifestPath $ownerValidationArguments.BundleManifestPath
        } catch {
            $rejected = $true
        }
        if (-not $rejected) {
            throw "Alpha owner metadata validator accepted $($damage.Kind) damage at $($damage.Path)."
        }
    }

    & (Join-Path $PSScriptRoot 'Stage-ReleaseBundle.ps1') `
        -MainApkPath $mainApk `
        -SetupHelperApkPath $helperApk `
        -Version '0.6.6-alpha.1' `
        -ExpectedChannel alpha `
        -SourceRevision $testSourceRevision `
        -SourceTree $testSourceTree `
        -OutputDirectory $repeatBundle
    $manifestHash =
        (Get-FileHash -LiteralPath (Join-Path $bundle 'bundle-manifest.json') -Algorithm SHA256).Hash
    $repeatManifestHash =
        (Get-FileHash -LiteralPath (Join-Path $repeatBundle 'bundle-manifest.json') -Algorithm SHA256).Hash
    if ($LASTEXITCODE -ne 0 -or $manifestHash -cne $repeatManifestHash) {
        throw 'Repeated staging with identical inputs did not produce a byte-identical manifest.'
    }

    $rejectionCases = @(
        @{ Version = '0.6.6'; Channel = 'alpha' },
        @{ Version = '0.6.6-alpha.2'; Channel = 'alpha' },
        @{ Version = '0.6.6-alpha.1'; Channel = 'alpha'; Signer = ('0' * 64) }
    )
    foreach ($rejection in $rejectionCases) {
        $rejected = $false
        try {
            $arguments = @{
                MainApkPath = $mainApk
                SetupHelperApkPath = $helperApk
                Version = $rejection.Version
                ExpectedChannel = $rejection.Channel
                SourceRevision = 'negative-test'
                SourceTree = 'negative-test'
                OutputDirectory = Join-Path $runDirectory ([guid]::NewGuid().ToString('N'))
            }
            if ($rejection.ContainsKey('Signer')) {
                $arguments.ExpectedSignerSha256 = $rejection.Signer
            }
            & (Join-Path $PSScriptRoot 'Stage-ReleaseBundle.ps1') @arguments | Out-Null
        } catch {
            $rejected = $true
        }
        if (-not $rejected) {
            throw "Release staging accepted an invalid tuple: $($rejection | ConvertTo-Json -Compress)"
        }
    }

    $sdkRoot = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { $env:ANDROID_SDK_ROOT }
    $realApkSigner =
        (Get-ChildItem -Path (Join-Path $sdkRoot 'build-tools\*\apksigner.bat') |
            Sort-Object FullName -Descending |
            Select-Object -First 1).FullName
    if (-not $realApkSigner) {
        throw 'apksigner was not found for the native-output regression test.'
    }
    Set-Content -LiteralPath $nativeOutputProxy -Encoding ascii -Value @(
        '@echo off',
        ('call "{0}" %* 1>&2' -f $realApkSigner),
        'exit /b %ERRORLEVEL%'
    )
    & (Join-Path $PSScriptRoot 'Stage-ReleaseBundle.ps1') `
        -MainApkPath $mainApk `
        -SetupHelperApkPath $helperApk `
        -Version '0.6.6-alpha.1' `
        -ExpectedChannel alpha `
        -SourceRevision ('3' * 40) `
        -SourceTree ('4' * 40) `
        -ApkSignerPath $nativeOutputProxy `
        -OutputDirectory $nativeOutputBundle
    if ($LASTEXITCODE -ne 0) {
        throw 'The native-output release bundle staging regression test failed.'
    }
    $nativeOutputManifest =
        Get-Content -Raw -LiteralPath (Join-Path $nativeOutputBundle 'bundle-manifest.json') |
            ConvertFrom-Json
    if ($nativeOutputManifest.signer_sha256 -cne $manifest.signer_sha256) {
        throw 'Native output object normalization changed the APK signer digest.'
    }

    Set-Content -LiteralPath $v2LabelProxy -Encoding ascii -Value @(
        '@echo off',
        ('@echo V2 Signer: certificate SHA-256 digest: {0}' -f $manifest.signer_sha256),
        '@exit /b 0'
    )
    & (Join-Path $PSScriptRoot 'Stage-ReleaseBundle.ps1') `
        -MainApkPath $mainApk `
        -SetupHelperApkPath $helperApk `
        -Version '0.6.6-alpha.1' `
        -ExpectedChannel alpha `
        -SourceRevision ('5' * 40) `
        -SourceTree ('6' * 40) `
        -ApkSignerPath $v2LabelProxy `
        -OutputDirectory $v2LabelBundle
    if ($LASTEXITCODE -ne 0) {
        throw 'The V2 signer-label release bundle staging regression test failed.'
    }
    $v2LabelManifest =
        Get-Content -Raw -LiteralPath (Join-Path $v2LabelBundle 'bundle-manifest.json') |
            ConvertFrom-Json
    if ($v2LabelManifest.signer_sha256 -cne $manifest.signer_sha256) {
        throw 'V2 signer-label normalization changed the APK signer digest.'
    }

    & (Join-Path $repoRoot 'gradlew.bat') `
        '-PrustyKioskReleaseVersion=0.6.6' `
        :app:assembleRelease `
        :setup-helper:assembleRelease
    if ($LASTEXITCODE -ne 0) {
        throw 'The local signed stable release build failed.'
    }
    & (Join-Path $PSScriptRoot 'Stage-ReleaseBundle.ps1') `
        -MainApkPath $mainApk `
        -SetupHelperApkPath $helperApk `
        -Version '0.6.6' `
        -ExpectedChannel stable `
        -SourceRevision 'local-stable-release-pipeline-test' `
        -SourceTree 'local-stable-release-pipeline-tree' `
        -OutputDirectory $stableBundle
    if ($LASTEXITCODE -ne 0) {
        throw 'The stable release bundle staging test failed.'
    }
    $stableManifest =
        Get-Content -Raw -LiteralPath (Join-Path $stableBundle 'bundle-manifest.json') |
            ConvertFrom-Json
    $stableNames = @($stableManifest.files.name | Sort-Object)
    $expectedStableNames = @(
        'RUSTY-KIOSK-LICENSE.txt',
        'RUSTY-KIOSK-SOURCE.txt',
        'rusty-kiosk-setup-helper.apk',
        'rusty-kiosk.apk'
    ) | Sort-Object
    if ($stableManifest.channel -cne 'stable' -or
        $stableManifest.prerelease -ne $false -or
        $stableManifest.tag -cne 'v0.6.6' -or
        $stableManifest.version -cne '0.6.6' -or
        $stableManifest.version_code -ne 60699 -or
        $stableManifest.signer_sha256 -cne $manifest.signer_sha256 -or
        ($stableNames -join "`n") -cne ($expectedStableNames -join "`n")) {
        throw 'The stable artifact tuple or consumer-compatible filenames regressed.'
    }
    $stableMain = @($stableManifest.files | Where-Object name -eq 'rusty-kiosk.apk')
    $stableHelper = @($stableManifest.files | Where-Object name -eq 'rusty-kiosk-setup-helper.apk')
    if ($stableMain.Count -ne 1 -or
        $stableMain[0].package_name -cne 'io.github.mesmerprism.rustykiosk' -or
        $stableMain[0].version_name -cne '0.6.6' -or
        $stableMain[0].version_code -ne 60699 -or
        $stableHelper.Count -ne 1 -or
        $stableHelper[0].package_name -cne 'io.github.mesmerprism.rustykiosk.setuphelper' -or
        $stableHelper[0].version_name -cne '0.6.6' -or
        $stableHelper[0].version_code -ne 60699) {
        throw 'The stable APK package/version identities regressed.'
    }

    Write-Output 'Rusty Kiosk signed stable/alpha release-pipeline test passed.'
}
finally {
    Remove-Item -LiteralPath $runDirectory -Recurse -Force -ErrorAction SilentlyContinue
    Remove-Item -LiteralPath $mainApk -Force -ErrorAction SilentlyContinue
    Remove-Item -LiteralPath $helperApk -Force -ErrorAction SilentlyContinue
    Remove-Item Env:\RUSTY_KIOSK_KEYSTORE_PATH -ErrorAction SilentlyContinue
    Remove-Item Env:\RUSTY_KIOSK_KEYSTORE_PASSWORD -ErrorAction SilentlyContinue
    Remove-Item Env:\RUSTY_KIOSK_KEY_ALIAS -ErrorAction SilentlyContinue
    Remove-Item Env:\RUSTY_KIOSK_KEY_PASSWORD -ErrorAction SilentlyContinue
}
