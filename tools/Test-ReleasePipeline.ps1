[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$repoRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$artifactsRoot = [IO.Path]::GetFullPath((Join-Path $repoRoot 'artifacts'))
New-Item -ItemType Directory -Path $artifactsRoot -Force | Out-Null
$runDirectory = [IO.Path]::GetFullPath((Join-Path $artifactsRoot ("release-pipeline-test-{0}" -f [guid]::NewGuid().ToString('N'))))
if (-not $runDirectory.StartsWith($artifactsRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'The release pipeline test directory escaped the repository artifacts root.'
}
New-Item -ItemType Directory -Path $runDirectory | Out-Null

$testPassword = 'local-release-pipeline-test'
$keystore = Join-Path $runDirectory 'test-release.jks'
$bundle = Join-Path $runDirectory 'bundle'
$mainApk = Join-Path $repoRoot 'app\build\outputs\apk\release\app-release.apk'
$helperApk = Join-Path $repoRoot 'setup-helper\build\outputs\apk\release\setup-helper-release.apk'

try {
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
    if ($LASTEXITCODE -ne 0) { throw 'Temporary signing-key generation failed.' }

    $env:RUSTY_KIOSK_KEYSTORE_PATH = $keystore
    $env:RUSTY_KIOSK_KEYSTORE_PASSWORD = $testPassword
    $env:RUSTY_KIOSK_KEY_ALIAS = 'release-test'
    $env:RUSTY_KIOSK_KEY_PASSWORD = $testPassword
    & (Join-Path $repoRoot 'gradlew.bat') :app:assembleRelease :setup-helper:assembleRelease
    if ($LASTEXITCODE -ne 0) { throw 'The local signed release build failed.' }

    & (Join-Path $PSScriptRoot 'Stage-ReleaseBundle.ps1') `
        -MainApkPath $mainApk `
        -SetupHelperApkPath $helperApk `
        -SourceRevision 'local-release-pipeline-test' `
        -OutputDirectory $bundle
    if ($LASTEXITCODE -ne 0) { throw 'The release bundle staging test failed.' }

    $manifest = Get-Content -Raw -LiteralPath (Join-Path $bundle 'bundle-manifest.json') | ConvertFrom-Json
    if ($manifest.schema -ne 'meta.quest.file_manager.rusty_kiosk_bundle.v1' -or
        $manifest.build_type -ne 'release' -or
        [string]::IsNullOrWhiteSpace($manifest.signer_sha256)) {
        throw 'The staged release manifest did not record the expected schema, build type, and signer.'
    }

    Write-Output 'Rusty Kiosk signed release-pipeline test passed.'
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
