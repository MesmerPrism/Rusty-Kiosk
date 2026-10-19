# SPDX-License-Identifier: AGPL-3.0-or-later
[CmdletBinding()]
param(
  [Parameter(Mandatory)][string]$Serial,
  [Parameter(Mandatory)][ValidatePattern('^[A-Za-z0-9._-]{1,128}$')][string]$CanonicalSerial,
  [Parameter(Mandatory)][string]$Artifact,
  [Parameter(Mandatory)][ValidatePattern('^[0-9a-f]{64}$')][string]$ExpectedSha256,
  [Parameter(Mandatory)][ValidatePattern('^[0-9a-f]{64}$')][string]$ExpectedSourceId,
  [ValidateSet('stable','labs')][string]$ProductChannel = 'stable'
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$ProductChannel = $ProductChannel.ToLowerInvariant()
# The serial is passed as an argv value to adb, never interpolated in remote shell text.
if ([string]::IsNullOrWhiteSpace($Serial)) { throw 'An explicit ADB serial is required.' }
$adb = (Get-Command adb -ErrorAction Stop).Source
$resolved = (Resolve-Path -LiteralPath $Artifact).Path
if ([IO.Path]::GetFileName($resolved) -ne 'rusty-kiosk-shell-companion.jar') { throw 'Select the companion JAR produced by Build-RustyKioskShellCompanion.ps1.' }
$buildReceipt = Get-Content -LiteralPath (Join-Path (Split-Path $resolved -Parent) 'BUILD.json') -Raw | ConvertFrom-Json
$expectedHash = (Get-FileHash -LiteralPath $resolved -Algorithm SHA256).Hash.ToLowerInvariant()
$kioskPackage = if ($ProductChannel -ceq 'labs') { 'io.github.mesmerprism.rustykiosk.labs' } else { 'io.github.mesmerprism.rustykiosk' }
if ($buildReceipt.schema -cne 'rusty-kiosk.shell-companion.build.v1' -or $buildReceipt.sha256 -cne $expectedHash -or
    $ExpectedSha256 -cne $expectedHash -or $buildReceipt.source_id -cne $ExpectedSourceId -or
    $buildReceipt.product_channel -cne $ProductChannel -or $buildReceipt.kiosk_package -cne $kioskPackage -or
    $buildReceipt.bytes -ne (Get-Item -LiteralPath $resolved).Length -or $buildReceipt.source_worktree_dirty -ne $false -or
    $buildReceipt.pure_tests -cne 'PASS' -or -not $buildReceipt.fixtures_excluded) { throw 'Approved clean source/artifact/channel receipt mismatch.' }
$canonicalReadback = ((& $adb -s $Serial shell -T 'getprop ro.serialno') -join "`n").Trim()
if ($LASTEXITCODE -ne 0 -or $canonicalReadback -cne $CanonicalSerial) { throw 'Canonical device identity mismatch; bootstrap was not admitted.' }
$session = [Guid]::NewGuid().ToString()
$base = '/data/local/tmp/rusty-kiosk-shell-companion'; $stage = "$base/$session"
$preflight = & $adb -s $Serial shell -T 'test "$(id -u)" = 2000 && command -v nohup && command -v app_process && test ! -e /data/local/tmp/rusty-kiosk-shell-companion/ACTIVE'
if ($LASTEXITCODE -ne 0) { throw 'A genuine shell, nohup/app_process, and no existing companion owner are required.' }
$boot = ((& $adb -s $Serial shell -T 'cat /proc/sys/kernel/random/boot_id') -join "`n").Trim()
$parsedBoot = [Guid]::Empty
if ($LASTEXITCODE -ne 0 -or -not [Guid]::TryParseExact($boot,'D',[ref]$parsedBoot) -or $parsedBoot.ToString() -cne $boot) { throw 'Exact device boot identity unavailable.' }
& $adb -s $Serial shell -T "mkdir -p '$base' && mkdir '$stage'"
if ($LASTEXITCODE -ne 0) { throw 'Fresh companion stage creation failed.' }
& $adb -s $Serial push $resolved "$stage/companion.jar"
if ($LASTEXITCODE -ne 0) { throw "Push failed; own inactive stage retained: $stage" }
$remoteHash = (& $adb -s $Serial shell -T "sha256sum '$stage/companion.jar'") -join "`n"
if ($LASTEXITCODE -ne 0 -or -not $remoteHash.StartsWith($expectedHash)) { throw 'Remote companion JAR pin mismatch.' }
# All substituted remote tokens are generated canonical UUID/fixed paths, never user shell input.
$launch = "nohup app_process -Djava.class.path='$stage/companion.jar' /system/bin io.github.mesmerprism.rustykiosk.shellcompanion.Main --session '$session' </dev/null >'$stage/stdout.jsonl' 2>'$stage/stderr.txt' &"
& $adb -s $Serial shell -T $launch
if ($LASTEXITCODE -ne 0) { throw "Launch transport unknown; do not retry. Inspect own stage $stage" }
$deadline = [DateTime]::UtcNow.AddSeconds(30)
$ready = $null
while ([DateTime]::UtcNow -lt $deadline) {
  $snapshot = @(& $adb -s $Serial shell -T "if test -f '$stage/FAIL.json'; then echo FAIL; cat '$stage/FAIL.json'; elif test -f '$stage/FINAL.json'; then echo FINAL; cat '$stage/FINAL.json'; elif test -f '$stage/READY.json'; then echo READY; cat '$stage/READY.json'; else echo PENDING; fi")
  if ($LASTEXITCODE -ne 0) { throw "Readiness transport unknown; no retry. Retain own stage $stage" }
  if ($snapshot.Count -gt 0 -and $snapshot[0] -eq 'FINAL') { throw "Companion retired before readiness; review own $stage/FINAL.json and stderr.txt." }
  if ($snapshot.Count -gt 0 -and $snapshot[0] -eq 'FAIL') { throw "Companion failed before an API call; review own $stage/FAIL.json and stderr.txt. No relaunch was attempted." }
  if ($snapshot.Count -gt 1 -and $snapshot[0] -eq 'READY') { $ready = ($snapshot[1..($snapshot.Count-1)] -join "`n") | ConvertFrom-Json; break }
  Start-Sleep -Milliseconds 200
}
if ($null -eq $ready) { throw "Companion readiness not established; no relaunch or cleanup. Inspect own stage $stage" }
if ($ready.session -cne $session -or $ready.boot -cne $boot -or $ready.uid -ne 2000 -or $ready.pid -le 0 -or
  $ready.source_id -cne $ExpectedSourceId -or $ready.product_channel -cne $ProductChannel -or
  -not $ready.api_enabled -or -not $ready.provider_registered -or -not $ready.native_reader_alive) { throw 'Typed own readiness identity/capability mismatch.' }
$helperPid = [int]$ready.pid
$stat = ((& $adb -s $Serial shell -T "cat /proc/$helperPid/stat") -join "`n").Trim()
if ($LASTEXITCODE -ne 0 -or $stat.IndexOf(')') -lt 0) { throw 'READY process is no longer present; no relaunch.' }
$fields = $stat.Substring($stat.LastIndexOf(')')+2).Split(' ',[StringSplitOptions]::RemoveEmptyEntries)
$uidStatus = (& $adb -s $Serial shell -T "cat /proc/$helperPid/status") -join "`n"
if ($LASTEXITCODE -ne 0 -or $fields.Count -lt 20 -or $fields[19] -cne [string]$ready.start_ticks -or $uidStatus -notmatch '(?m)^Uid:\s+2000\s+2000\s+2000\s+2000\s*$') { throw 'READY process birth/UID no longer matches.' }
$bootAfter = ((& $adb -s $Serial shell -T 'cat /proc/sys/kernel/random/boot_id') -join "`n").Trim()
if ($LASTEXITCODE -ne 0 -or $bootAfter -cne $boot) { throw 'Device boot changed during readiness.' }
[ordered]@{ schema='rusty-kiosk.shell-companion.bootstrap.v1'; canonical_serial=$CanonicalSerial; product_channel=$ProductChannel;
  source_id=$ExpectedSourceId; session=$session; boot=$boot; pid=$helperPid; reader_pid=$ready.reader_pid; reader_start_ticks=$ready.reader_start_ticks;
  stage=$stage; artifact_sha256=$expectedHash; status='READY'; readiness='Genuine shell API/provider/native reader ready. The app still owns mode selection and guard arming. ADB may disconnect.' } | ConvertTo-Json
