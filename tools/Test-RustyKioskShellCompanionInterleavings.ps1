# SPDX-License-Identifier: AGPL-3.0-or-later
[CmdletBinding()]
param([string]$JavaHome=$env:JAVA_HOME, [string]$MainSource='')
$ErrorActionPreference='Stop'
Set-StrictMode -Version Latest
$repo=Split-Path $PSScriptRoot -Parent
$module=Join-Path $repo 'shell-companion'
if (-not $MainSource) { $MainSource=Join-Path $module 'src/main/java/io/github/mesmerprism/rustykiosk/shellcompanion/Main.java' }
$source=[IO.File]::ReadAllText($MainSource).Replace("`r`n","`n")
$methods=@()
foreach($pair in @(@('    void nativeEvent(', '    void schedule('), @('    void sampleTarget(', '    static final class AwaitingFocus'))) {
  $start=$source.IndexOf($pair[0],[StringComparison]::Ordinal)
  $end=$source.IndexOf($pair[1],[StringComparison]::Ordinal)
  if($start -lt 0 -or $end -le $start) { throw 'Production method boundary changed; update the source binding deliberately.' }
  $methods+=$source.Substring($start,$end-$start)
}
$selected=$methods -join "`n"
$templatePath=Join-Path $module 'src/host-test/ProtocolInterleavingTests.java.in'
$template=[IO.File]::ReadAllText($templatePath)
if([regex]::Matches($template,'// EXACT_PRODUCTION_METHODS').Count -ne 1) { throw 'Host fixture source binding is ambiguous.' }
$build=Join-Path $module ('build/interleavings-'+[Guid]::NewGuid().ToString('N'))
[void](New-Item -ItemType Directory -Path $build -Force)
$generated=Join-Path $build 'ProtocolInterleavingTests.java'
[IO.File]::WriteAllText($generated,$template.Replace('// EXACT_PRODUCTION_METHODS',$selected),[Text.UTF8Encoding]::new($false))
$java=Join-Path $JavaHome 'bin/java.exe'; $javac=Join-Path $JavaHome 'bin/javac.exe'
if(-not (Test-Path -LiteralPath $java)) { $java=Join-Path $JavaHome 'bin/java';$javac=Join-Path $JavaHome 'bin/javac' }
$production=Join-Path $module 'src/main/java/io/github/mesmerprism/rustykiosk/shellcompanion'
$inputs=@($generated,(Join-Path $production 'CloseState.java'),(Join-Path $production 'NativeEvent.java'),(Join-Path $production 'TargetIdentity.java'))
& $javac --release 8 -encoding UTF-8 -d $build @inputs
if($LASTEXITCODE -ne 0) { throw 'Production-method interleaving host compilation failed.' }
& $java -cp $build io.github.mesmerprism.rustykiosk.shellcompanion.ProtocolInterleavingTests
if($LASTEXITCODE -ne 0) { throw 'Production-method interleaving regressions failed.' }
$receipt=[ordered]@{schema='rusty.kiosk.shell_companion.interleavings.v1';result='PASS';
  production_main_sha256=(Get-FileHash -LiteralPath $MainSource -Algorithm SHA256).Hash.ToLowerInvariant();
  selected_methods_sha256=[Convert]::ToHexString([Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes($selected))).ToLowerInvariant();
  fixture_sha256=(Get-FileHash -LiteralPath $templatePath -Algorithm SHA256).Hash.ToLowerInvariant();
  binding='Exact nativeEvent/sampleTarget production method bodies; deterministic host scheduling and Android-free doubles';device_effects=$false}
$receipt | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $build 'RESULT.json') -Encoding utf8NoBOM
$receipt | ConvertTo-Json
