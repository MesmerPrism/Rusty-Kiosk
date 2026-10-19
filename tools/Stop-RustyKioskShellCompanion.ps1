# SPDX-License-Identifier: AGPL-3.0-or-later
[CmdletBinding()]
param(
  [Parameter(Mandatory)][string]$Serial,
  [Parameter(Mandatory)][ValidatePattern('^[A-Za-z0-9._-]{1,128}$')][string]$CanonicalSerial,
  [Parameter(Mandatory)][string]$Session,
  [Parameter(Mandatory)][ValidatePattern('^[0-9a-f]{64}$')][string]$ExpectedSourceId
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$parsed = [Guid]::Empty
if ([string]::IsNullOrWhiteSpace($Serial) -or -not [Guid]::TryParseExact($Session,'D',[ref]$parsed) -or $parsed.ToString() -cne $Session) { throw 'Explicit serial and canonical lowercase session UUID required.' }
$adb = (Get-Command adb -ErrorAction Stop).Source
$base='/data/local/tmp/rusty-kiosk-shell-companion'; $stage="$base/$Session"
$ownerText = (& $adb -s $Serial shell -T "cat '$base/ACTIVE/owner.json'") -join "`n"
if ($LASTEXITCODE -ne 0) { throw 'No readable live companion owner; inspect the selected session FINAL.json before any cleanup.' }
$owner = $ownerText | ConvertFrom-Json
if ($owner.session -cne $Session -or $owner.source_id -cne $ExpectedSourceId) { throw 'Exact source/session does not own the companion admission lock.' }
$canonicalReadback = ((& $adb -s $Serial shell -T 'getprop ro.serialno') -join "`n").Trim()
if ($LASTEXITCODE -ne 0 -or $canonicalReadback -cne $CanonicalSerial) { throw 'Canonical device identity mismatch; no STOP was written.' }
if ([string]$owner.pid -notmatch '^[1-9][0-9]{0,9}$' -or [string]$owner.start_ticks -notmatch '^[0-9]+$') { throw 'Owner process identity is malformed.' }
$helperPid = [int]$owner.pid
$boot = ((& $adb -s $Serial shell -T 'cat /proc/sys/kernel/random/boot_id') -join "`n").Trim()
if ($LASTEXITCODE -ne 0 -or $boot -cne $owner.boot) { throw 'Owner belongs to a different boot; no STOP was written.' }
$stat = ((& $adb -s $Serial shell -T "cat /proc/$helperPid/stat") -join "`n").Trim()
if ($LASTEXITCODE -ne 0 -or $stat.LastIndexOf(')') -lt 0) { throw 'Owner process is absent; review its FINAL.json without restarting it.' }
$fields = $stat.Substring($stat.LastIndexOf(')')+2).Split(' ',[StringSplitOptions]::RemoveEmptyEntries)
if ($fields.Count -lt 20 -or $fields[19] -cne [string]$owner.start_ticks) { throw 'Owner process birth changed; no STOP was written.' }
$uidStatus = (& $adb -s $Serial shell -T "cat /proc/$helperPid/status") -join "`n"
if ($LASTEXITCODE -ne 0 -or $uidStatus -notmatch '(?m)^Uid:\s+2000\s+2000\s+2000\s+2000\s*$') { throw 'Owner is not the genuine shell UID; no STOP was written.' }
& $adb -s $Serial shell -T "test -d '$stage/state' && touch '$stage/STOP'"
if ($LASTEXITCODE -ne 0) { throw 'Stop request transport unknown; no kill or replay was attempted.' }
[ordered]@{ session=$Session; status='STOP_REQUESTED'; retirement='Admission ends on the next 100ms tick. Pending capability reads and original calls remain held until their original callback. Before replacement verify exact FINAL.json, helper and reader process absence, and ACTIVE absence. This request alone proves no retirement; no caller was killed or evidence deleted.' } | ConvertTo-Json
