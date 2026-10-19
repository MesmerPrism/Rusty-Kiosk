# SPDX-License-Identifier: AGPL-3.0-or-later
# Pure host policy tests; no ADB, Gradle, device, Pester or filesystem fixture dependency.
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
. (Join-Path $PSScriptRoot 'CompanionQuarantinePolicy.ps1')
$session = '11111111-1111-4111-8111-111111111111'
$prior = '22222222-2222-4222-8222-222222222222'
$current = '33333333-3333-4333-8333-333333333333'
$id = '44444444-4444-4444-8444-444444444444'
$count = 0
function Fixture {
  $intent = [ordered]@{command_id=$id;generation=-7;source_uptime_ms=100;due_uptime_ms=180;function='com.oculus.vrshell.appfunctions.ShellAppFunctions#openNavigator';parameters='empty'}
  $result = [ordered]@{command_id=$id;outcome='callback';success=$true;dispatch_uptime_ms=190;callback_uptime_ms=210;callback_error=$null;effect_verified=$false}
  $common = @{schema='rusty-kiosk.shell-companion.v1';session=$session;pid=7063}
  $dispatch = $common.Clone(); $dispatch.event='API_DISPATCH_INTENT'; $dispatch.command_id=$id; $dispatch.generation=-7; $dispatch.source_uptime_ms=100; $dispatch.dispatch_uptime_ms=190
  $callback = $common.Clone(); $callback.event='CALLBACK_KNOWN'; $callback.command_id=$id; $callback.success=$true; $callback.dispatch_uptime_ms=190; $callback.callback_uptime_ms=210; $callback.effect_verified=$false
  $withdraw = $common.Clone(); $withdraw.event='WITHDRAWAL_UNKNOWN_HELD'
  $capabilityIntent = [ordered]@{schema='rusty.kiosk.shell_companion.capability.v1';session=$session;boot=$prior;pid=7063;function='com.oculus.vrshell.appfunctions.ShellAppFunctions#openNavigator';query='is-enabled';dispatch_uptime_ms=10}
  $capabilityResult = [ordered]@{schema='rusty.kiosk.shell_companion.capability.v1';session=$session;boot=$prior;pid=7063;function='com.oculus.vrshell.appfunctions.ShellAppFunctions#openNavigator';query='is-enabled';dispatch_uptime_ms=10;callback_uptime_ms=20;enabled=$true;error=$null}
  return @{
    Session=$session;ExpectedPriorBoot=$prior;ExpectedCurrentBoot=$current;ExpectedSourceId=('a'*64);ProductChannel='stable'
    OwnerText=([ordered]@{session=$session;boot=$prior;pid=7063;start_ticks='12345';source_id=('a'*64);product_channel='stable'} | ConvertTo-Json -Compress)
    StateNames=@("INTENT-$id.json","RESULT-$id.json",'CAPABILITY-INTENT.json','CAPABILITY-RESULT.json')
    StateTexts=@{"INTENT-$id.json"=($intent | ConvertTo-Json -Compress);"RESULT-$id.json"=($result | ConvertTo-Json -Compress);'CAPABILITY-INTENT.json'=($capabilityIntent | ConvertTo-Json -Compress);'CAPABILITY-RESULT.json'=($capabilityResult | ConvertTo-Json -Compress)}
    JournalTexts=@((@($dispatch,$callback,$withdraw) | ForEach-Object {ConvertTo-Json -InputObject $_ -Compress}) -join "`n")
  }
}
function Reject([scriptblock]$Change) {
  $fixture=Fixture; & $Change $fixture
  $rejected=$false
  try { [void](Test-CompanionPostbootQuarantine @fixture) } catch { $rejected=$true }
  if (-not $rejected) { throw 'Expected fixture rejection.' }
  $script:count++
}
$fixture=Fixture
$proof=Test-CompanionPostbootQuarantine @fixture
if ($proof.original_count -ne 1 -or $proof.journal_originals_joined -ne 1) { throw 'Valid known original did not join.' }; $count++
if (-not $proof.capability_read_joined) { throw 'Capability original callback was not joined.' }; $count++
Reject {param($f) $f.StateNames=@($f.StateNames | Where-Object { $_ -cne 'CAPABILITY-RESULT.json' });$f.StateTexts.Remove('CAPABILITY-RESULT.json')}
Reject {param($f) $f.StateTexts['CAPABILITY-RESULT.json']=$f.StateTexts['CAPABILITY-RESULT.json'].Replace('"dispatch_uptime_ms":10','"dispatch_uptime_ms":11')}
Reject {param($f) $f.StateTexts['CAPABILITY-RESULT.json']=$f.StateTexts['CAPABILITY-RESULT.json'].Replace($prior,$current)}
Reject {param($f) $f.StateTexts['CAPABILITY-RESULT.json']=$f.StateTexts['CAPABILITY-RESULT.json'].Replace('"callback_uptime_ms":20','"callback_uptime_ms":9')}
Reject {param($f) $f.ExpectedCurrentBoot=$prior}
Reject {param($f) $f.OwnerText=$f.OwnerText.Replace($session,$id)}
Reject {param($f) $f.OwnerText=$f.OwnerText.Replace('12345','invalid')}
Reject {param($f) $f.ExpectedSourceId='b'*64}
Reject {param($f) $f.ProductChannel='labs'}
Reject {param($f) $f.OwnerText=$f.OwnerText.Replace('{','{"Count":4,')}
Reject {param($f) $f.StateNames=@("INTENT-$id.json");$f.StateTexts.Remove("RESULT-$id.json")}
Reject {param($f) $f.StateTexts["RESULT-$id.json"]=$f.StateTexts["RESULT-$id.json"].Replace('callback"','unknown"')}
Reject {param($f) $f.StateNames+=@("RESULT-$id.json")}
Reject {param($f) $f.StateNames+=@('UNKNOWN.json');$f.StateTexts['UNKNOWN.json']='{}'}
Reject {param($f) $f.StateTexts["RESULT-$id.json"]=$f.StateTexts["RESULT-$id.json"].Replace($id,$session)}
Reject {param($f) $f.JournalTexts=@($f.JournalTexts[0] -replace 'CALLBACK_KNOWN','OTHER_EVENT')}
Reject {param($f) $f.JournalTexts=@($f.JournalTexts[0] -replace 'WITHDRAWAL_UNKNOWN_HELD','OTHER_EVENT')}
Reject {param($f) $f.JournalTexts+=@(($f.JournalTexts[0] -split "`n")[1])}
Reject {param($f) $f.StateTexts["RESULT-$id.json"]=$f.StateTexts["RESULT-$id.json"].Replace('210','189')}
Reject {param($f) $f.StateTexts["INTENT-$id.json"]=$f.StateTexts["INTENT-$id.json"].Replace('openNavigator','otherFunction')}
Reject {param($f) $f.StateTexts["RESULT-$id.json"]=$f.StateTexts["RESULT-$id.json"].Replace('false','true')}
$duplicateRejected=$false
try {[void](ConvertFrom-CompanionReceipt '{"session":"a","session":"b"}')} catch {$duplicateRejected=$true}
if (-not $duplicateRejected) {throw 'Duplicate JSON critical field accepted.'};$count++
# Non-generic Task completion must never enter the raw-byte output pipeline.
$source=[IO.MemoryStream]::new([byte[]](65,66));$destination=[IO.MemoryStream]::new()
try {
  [void]$source.CopyToAsync($destination).GetAwaiter().GetResult()
  $raw=$destination.ToArray()
  if ($raw -isnot [byte[]] -or [Text.Encoding]::UTF8.GetString($raw) -cne 'AB') {throw 'Async byte-copy regression.'}
} finally {$source.Dispose();$destination.Dispose()}
$count++
$closedCommand="cat '/fixed/receipt.json'`nprintf '%s\n' 'fixed'"
$argv=@(Get-CompanionQuarantineAdbArguments -Serial 'explicit-route' -Command $closedCommand)
if ($argv.Count -ne 4 -or $argv[0] -cne '-s' -or $argv[1] -cne 'explicit-route' -or
    $argv[2] -cne 'exec-out' -or $argv[3] -cne $closedCommand) {throw 'Raw-byte exec-out argument layout changed.'}
$count++
[ordered]@{schema='rusty-kiosk.shell-companion.quarantine-tests.v1';status='PASS';checks=$count;device_actions=$false} | ConvertTo-Json
