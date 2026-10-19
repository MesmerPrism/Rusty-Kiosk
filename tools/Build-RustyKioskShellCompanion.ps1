# SPDX-License-Identifier: AGPL-3.0-or-later
[CmdletBinding()]
param(
  [ValidateSet('stable','labs')][string]$ProductChannel = 'stable',
  [string]$JavaHome = $env:JAVA_HOME,
  [string]$AndroidSdkRoot = $(if ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT } else { $env:ANDROID_HOME })
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$ProductChannel = $ProductChannel.ToLowerInvariant()
$repo = Split-Path $PSScriptRoot -Parent
$module = Join-Path $repo 'shell-companion'
& (Join-Path $PSScriptRoot 'Test-QuestShellContext.ps1') -RepoRoot $repo | Out-Host
$upstreamPath = Join-Path $repo 'vendor/quest-shell-context/UPSTREAM.json'
$upstream = Get-Content -Raw -LiteralPath $upstreamPath | ConvertFrom-Json -Depth 12
if (-not $JavaHome -or -not $AndroidSdkRoot) { throw 'Set JAVA_HOME and ANDROID_SDK_ROOT or pass their paths.' }
$java = Join-Path $JavaHome 'bin/java.exe'
$javac = Join-Path $JavaHome 'bin/javac.exe'
$jar = Join-Path $JavaHome 'bin/jar.exe'
if (-not (Test-Path -LiteralPath $java)) { $java = Join-Path $JavaHome 'bin/java'; $javac = Join-Path $JavaHome 'bin/javac'; $jar = Join-Path $JavaHome 'bin/jar' }
$android = Join-Path $AndroidSdkRoot 'platforms/android-34/android.jar'
$d8 = @(Get-ChildItem -LiteralPath (Join-Path $AndroidSdkRoot 'build-tools') -Directory | Where-Object { Test-Path -LiteralPath (Join-Path $_.FullName 'lib/d8.jar') } | Sort-Object Name -Descending | Select-Object -First 1)
if ($d8.Count -ne 1) { throw 'Install Android SDK build-tools with D8.' }
$d8jar = Join-Path $d8[0].FullName 'lib/d8.jar'
foreach ($inputPath in @($java,$javac,$jar,$android,$d8jar)) { if (-not (Test-Path -LiteralPath $inputPath -PathType Leaf)) { throw "Missing tool input: $inputPath" } }
$build = Join-Path $module ('build/' + [Guid]::NewGuid().ToString('N'))
$classes = Join-Path $build 'classes'; $tests = Join-Path $build 'tests'; $dex = Join-Path $build 'dex'; $generated = Join-Path $build 'generated'
New-Item -ItemType Directory -Path $classes,$tests,$dex,$generated -Force | Out-Null
$sources = @(Get-ChildItem -LiteralPath (Join-Path $module 'src/main/java') -Recurse -Filter '*.java' | Sort-Object FullName)
$factory = Join-Path $repo 'vendor/quest-shell-context/src/main/java/io/github/mesmerprism/rustyquest/shellrecenter/ShellApplicationContext.java'
$testSources = @(Get-ChildItem -LiteralPath (Join-Path $module 'src/test/java') -Recurse -Filter '*.java' | Sort-Object FullName)
$inputs = @($sources.FullName) + @($factory,$upstreamPath,(Join-Path $repo 'vendor/quest-shell-context/LICENSE')) + @($testSources.FullName) + @($java,$javac,$jar,$android,$d8jar,$PSCommandPath,(Join-Path $PSScriptRoot 'Test-QuestShellContext.ps1'))
$pins = @($inputs | ForEach-Object { [ordered]@{ path=$_; sha256=(Get-FileHash -LiteralPath $_ -Algorithm SHA256).Hash.ToLowerInvariant() } })
$sourceInventory = @(@($sources.FullName) + @($factory,$upstreamPath) | Sort-Object | ForEach-Object {
  [IO.Path]::GetRelativePath($repo,$_).Replace('\','/') + ' ' + (Get-FileHash -LiteralPath $_ -Algorithm SHA256).Hash.ToLowerInvariant()
})
$sourceText = ($sourceInventory -join "`n") + "`nproduct_channel $ProductChannel`n"
$sourceId = [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes($sourceText))).ToLowerInvariant()
$kioskPackage = if ($ProductChannel -ceq 'labs') { 'io.github.mesmerprism.rustykiosk.labs' } else { 'io.github.mesmerprism.rustykiosk' }
$identity = Join-Path $generated 'CompanionIdentity.java'
$identitySource = 'package io.github.mesmerprism.rustykiosk.shellcompanion; final class CompanionIdentity { static final String KIOSK_PACKAGE="' + $kioskPackage + '"; static final String PROVIDER_AUTHORITY=KIOSK_PACKAGE+".shell-companion"; static final String COMPONENT=KIOSK_PACKAGE+"/io.github.mesmerprism.rustykiosk.RustyKioskActivity"; static final String PRODUCT_CHANNEL="' + $ProductChannel + '"; static final String SOURCE_ID="' + $sourceId + '"; }'
[IO.File]::WriteAllText($identity, $identitySource, [Text.UTF8Encoding]::new($false))
$timer = [Diagnostics.Stopwatch]::StartNew()
& $javac --release 8 -encoding UTF-8 -cp $android -d $classes @($sources.FullName) $factory $identity
if ($LASTEXITCODE -ne 0) { throw 'Companion javac failed.' }
$pure = @($sources | Where-Object Name -in @('CloseState.java','EnabledReadState.java','NativeEvent.java','TargetIdentity.java','ClientFocus.java') | ForEach-Object FullName)
& $javac --release 8 -encoding UTF-8 -d $tests @pure $identity @($testSources.FullName)
if ($LASTEXITCODE -ne 0) { throw 'Pure Java test compilation failed.' }
& $java -cp $tests io.github.mesmerprism.rustykiosk.shellcompanion.CompanionTests
if ($LASTEXITCODE -ne 0) { throw 'Companion admission tests failed.' }
$classJar = Join-Path $build 'classes.jar'
& $jar cf $classJar -C $classes .
if ($LASTEXITCODE -ne 0) { throw 'Class JAR creation failed.' }
$entries = @(& $jar tf $classJar)
if ($LASTEXITCODE -ne 0 -or @($entries | Where-Object { $_ -match '^(android/|org/)|Tests|Conformance' }).Count -ne 0) { throw 'Companion production JAR contains fixture/framework classes.' }
& $java -cp $d8jar com.android.tools.r8.D8 --release --min-api 31 --lib $android --output $dex $classJar
if ($LASTEXITCODE -ne 0) { throw 'D8 failed.' }
$artifact = Join-Path $build 'rusty-kiosk-shell-companion.jar'
& $jar cf $artifact -C $dex .
if ($LASTEXITCODE -ne 0) { throw 'Dex JAR creation failed.' }
foreach ($pin in $pins) { if ((Get-FileHash -LiteralPath $pin.path -Algorithm SHA256).Hash.ToLowerInvariant() -ne $pin.sha256) { throw 'Build input changed during compilation.' } }
$revision = (& git -C $repo rev-parse HEAD).Trim()
$tree = (& git -C $repo rev-parse 'HEAD^{tree}').Trim()
$dirty = -not [string]::IsNullOrWhiteSpace((& git -C $repo status --porcelain | Out-String))
$receipt = [ordered]@{ schema='rusty-kiosk.shell-companion.build.v1'; product_channel=$ProductChannel; kiosk_package=$kioskPackage; source_id=$sourceId;
  observed_git_revision=$revision; observed_git_tree=$tree; source_worktree_dirty=$dirty;
  quest_revision=$upstream.revision; quest_tree=$upstream.tree; sdk_api=34; min_api=31; java_release=8; elapsed_ms=$timer.ElapsedMilliseconds;
  inputs=$pins; artifact=$artifact; bytes=(Get-Item -LiteralPath $artifact).Length; sha256=(Get-FileHash -LiteralPath $artifact -Algorithm SHA256).Hash.ToLowerInvariant(); pure_tests='PASS'; fixtures_excluded=$true; device_validation='NOT_RUN' }
$receiptPath = Join-Path $build 'BUILD.json'
$receipt | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $receiptPath -Encoding utf8NoBOM
$receipt | ConvertTo-Json -Depth 8
