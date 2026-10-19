# SPDX-License-Identifier: AGPL-3.0-or-later
#requires -Version 7.2
[CmdletBinding()]
param(
  [Parameter(Mandatory)][string]$Serial,
  [Parameter(Mandatory)][string]$CanonicalSerial,
  [Parameter(Mandatory)][string]$Session,
  [Parameter(Mandatory)][string]$ExpectedPriorBoot,
  [Parameter(Mandatory)][string]$ExpectedCurrentBoot,
  [Parameter(Mandatory)][ValidatePattern('^[0-9a-f]{64}$')][string]$ExpectedSourceId,
  [ValidateSet('stable','labs')][string]$ProductChannel = 'stable',
  [switch]$ValidateOnly
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$ProductChannel = $ProductChannel.ToLowerInvariant()
. (Join-Path $PSScriptRoot 'CompanionQuarantinePolicy.ps1')
foreach ($id in @($Session,$ExpectedPriorBoot,$ExpectedCurrentBoot)) { Assert-CompanionUuid $id }
if ([string]::IsNullOrWhiteSpace($Serial) -or $CanonicalSerial -cnotmatch '^[A-Za-z0-9._:-]{1,128}$' -or
    $ExpectedPriorBoot -ceq $ExpectedCurrentBoot) { throw 'Explicit route/canonical serial and distinct prior/current boots required.' }
$adb = (Get-Command adb -ErrorAction Stop).Source
$base = '/data/local/tmp/rusty-kiosk-shell-companion'
$stage = "$base/$Session"; $active = "$base/ACTIVE"
$quarantine = "$base/QUARANTINED-$Session-$ExpectedPriorBoot"

function Invoke-CompanionRead([string]$Command) {
  $info = [Diagnostics.ProcessStartInfo]::new()
  $info.FileName = $adb; $info.UseShellExecute = $false; $info.CreateNoWindow = $true
  $info.RedirectStandardOutput = $true; $info.RedirectStandardError = $true
  foreach ($arg in (Get-CompanionQuarantineAdbArguments -Serial $Serial -Command $Command)) { $info.ArgumentList.Add($arg) }
  $process = [Diagnostics.Process]::new(); $process.StartInfo = $info
  if (-not $process.Start()) { throw 'ADB child did not start.' }
  $bytes = [IO.MemoryStream]::new()
  $stdout = $process.StandardOutput.BaseStream.CopyToAsync($bytes)
  $stderr = $process.StandardError.ReadToEndAsync()
  [void]$process.WaitForExit(); [void]$stdout.GetAwaiter().GetResult(); $errorText = $stderr.GetAwaiter().GetResult()
  $exit = $process.ExitCode; $process.Dispose()
  $raw = $bytes.ToArray(); $bytes.Dispose()
  if ($exit -ne 0) { throw "Original ADB operation exited $exit; no retry/kill. Closed command: $Command`nStderr: $errorText" }
  return ,$raw
}
function Read-CompanionText([string]$Command) { return [Text.Encoding]::UTF8.GetString((Invoke-CompanionRead $Command)) }
function Hash-CompanionBytes([byte[]]$Bytes) { return [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($Bytes)).ToLowerInvariant() }
function Assert-CompanionDevice {
  if ((Read-CompanionText 'id -u').Trim() -cne '2000' -or
      (Read-CompanionText 'getprop ro.serialno').Trim() -cne $CanonicalSerial -or
      (Read-CompanionText 'cat /proc/sys/kernel/random/boot_id').Trim() -cne $ExpectedCurrentBoot) {
    throw 'Fresh genuine-shell/canonical-device/current-boot readback mismatch.'
  }
}
Assert-CompanionDevice
[void](Invoke-CompanionRead "test -d '$base' && test ! -L '$base' && test -d '$active' && test ! -L '$active' && test -f '$active/owner.json' && test ! -L '$active/owner.json' && test -d '$stage/state' && test ! -L '$stage' && test ! -L '$stage/state' && test ! -e '$stage/FINAL.json' && test ! -L '$stage/FINAL.json' && test ! -e '$quarantine' && test ! -L '$quarantine'")
$ownerBytes = Invoke-CompanionRead "cat '$active/owner.json'"
$ownerHash = Hash-CompanionBytes $ownerBytes
$activeNames = (Read-CompanionText "ls -1A '$active'").Trim()
if ($activeNames -cne 'owner.json') { throw 'Unexpected ACTIVE contents.' }
$inventoryBytes = Invoke-CompanionRead "ls -1A '$stage/state'"
$inventoryHash = Hash-CompanionBytes $inventoryBytes
$names = @([Text.Encoding]::UTF8.GetString($inventoryBytes) -split '\r?\n' | Where-Object { $_ -ne '' })
$stateTexts = @{}; $filePins = [Collections.Generic.List[object]]::new()
foreach ($name in $names) {
  if ($name -cnotmatch '^(INTENT|RESULT)-[0-9a-f-]{36}\.json$' -and
      $name -cnotin @('CAPABILITY-INTENT.json', 'CAPABILITY-RESULT.json')) { throw 'Unexpected state file; no remote path accepted.' }
  [void](Invoke-CompanionRead "test -f '$stage/state/$name' && test ! -L '$stage/state/$name'")
  $raw = Invoke-CompanionRead "cat '$stage/state/$name'"
  if ($raw.Length -gt 65536) { throw 'Oversized original receipt.' }
  $stateTexts[$name] = [Text.Encoding]::UTF8.GetString($raw)
  $filePins.Add([ordered]@{ name=$name; sha256=(Hash-CompanionBytes $raw) })
}
$journalTexts = [Collections.Generic.List[string]]::new()
foreach ($name in @('events.previous.jsonl','events.jsonl')) {
  $exists = (Read-CompanionText "if test -e '$stage/$name' || test -L '$stage/$name'; then echo PRESENT; else echo ABSENT; fi").Trim()
  if ($exists -ceq 'ABSENT') { continue }
  [void](Invoke-CompanionRead "test -f '$stage/$name' && test ! -L '$stage/$name'")
  $raw = Invoke-CompanionRead "cat '$stage/$name'"
  if ($raw.Length -gt 2097152) { throw 'Oversized retained companion journal.' }
  $journalTexts.Add([Text.Encoding]::UTF8.GetString($raw))
  $filePins.Add([ordered]@{ name=$name; sha256=(Hash-CompanionBytes $raw) })
}
$proof = Test-CompanionPostbootQuarantine -Session $Session -ExpectedPriorBoot $ExpectedPriorBoot -ExpectedCurrentBoot $ExpectedCurrentBoot -ExpectedSourceId $ExpectedSourceId -ProductChannel $ProductChannel -OwnerText ([Text.Encoding]::UTF8.GetString($ownerBytes)) -StateNames $names -StateTexts $stateTexts -JournalTexts $journalTexts.ToArray()
Assert-CompanionDevice
# Every substituted token below is a checked UUID, serial, lowercase hash or closed filename.
# One same-parent rename only. No old stage mutation, deletion, process operation or FINAL creation.
$guards = [Collections.Generic.List[string]]::new()
$guards.Add('set -eu')
$guards.Add('test "$(id -u)" = 2000')
$guards.Add("test `"`$(getprop ro.serialno)`" = '$CanonicalSerial'")
$guards.Add("test `"`$(cat /proc/sys/kernel/random/boot_id)`" = '$ExpectedCurrentBoot'")
$guards.Add("test -d '$base' && test ! -L '$base' && test -d '$stage/state' && test ! -L '$stage' && test ! -L '$stage/state'")
$guards.Add("test -d '$active' && test ! -L '$active' && test ! -L '$active/owner.json'")
$guards.Add("test `"`$(ls -1A '$active')`" = owner.json")
$guards.Add("test `"`$(sha256sum '$active/owner.json' | cut -d ' ' -f 1)`" = '$ownerHash'")
$guards.Add("test `"`$(ls -1A '$stage/state' | sha256sum | cut -d ' ' -f 1)`" = '$inventoryHash'")
foreach ($pin in $filePins) {
  $path = if ($pin.name.StartsWith('events.')) { "$stage/$($pin.name)" } else { "$stage/state/$($pin.name)" }
  $guards.Add("test -f '$path' && test ! -L '$path'")
  $guards.Add("test `"`$(sha256sum '$path' | cut -d ' ' -f 1)`" = '$($pin.sha256)'")
}
$guards.Add("test ! -e '$stage/FINAL.json' && test ! -L '$stage/FINAL.json'")
$guards.Add("test ! -e '$quarantine' && test ! -L '$quarantine'")
if (-not $ValidateOnly) { $guards.Add("mv '$active' '$quarantine'") }
$checkedGuards = [Collections.Generic.List[string]]::new()
$checkedGuards.Add($guards[0])
for ($index=1; $index -lt $guards.Count; $index++) {
  # POSIX grouping prints only the failing closed guard's ordinal; no command/value tracing.
  $checkedGuards.Add("if ! ( $($guards[$index]) ); then printf '%s\n' 'QUARANTINE_GUARD_${index}_FAILED' >&2; exit 1; fi")
}
[void](Invoke-CompanionRead ($checkedGuards -join "`n"))
if (-not $ValidateOnly) {
  Assert-CompanionDevice
  [void](Invoke-CompanionRead "test ! -e '$active' && test ! -L '$active' && test -d '$quarantine' && test ! -L '$quarantine' && test ! -L '$quarantine/owner.json'")
  $afterHash = Hash-CompanionBytes (Invoke-CompanionRead "cat '$quarantine/owner.json'")
  if ($afterHash -cne $ownerHash) { throw 'Quarantine owner readback mismatch; no retry.' }
}
[ordered]@{
  schema='rusty-kiosk.shell-companion.postboot-quarantine.v1'
  status=$(if ($ValidateOnly) { 'VALIDATED_NOT_MOVED' } else { 'POSTBOOT_QUARANTINED' })
  canonical_serial=$CanonicalSerial; session=$Session; prior_boot=$ExpectedPriorBoot; current_boot=$ExpectedCurrentBoot
  prior_owner=$proof.prior_owner; owner_sha256=$ownerHash; original_count=$proof.original_count
  journal_originals_joined=$proof.journal_originals_joined; evidence_pins=$filePins.ToArray()
  capability_read_joined=$proof.capability_read_joined
  prior_withdrawal_confirmed=$false; prior_stage_preserved=$true; synthetic_final_created=$false
  old_process_retirement_basis='different verified device boot; not a graceful companion FINAL'
} | ConvertTo-Json -Depth 8
