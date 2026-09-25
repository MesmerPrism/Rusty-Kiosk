[CmdletBinding()]
param(
  [Parameter(Mandatory)][ValidatePattern('^[A-Za-z0-9._-]+$')][string]$Serial,
  [Parameter(Mandatory)][ValidateSet('status','catalog','select','enable','wait')][string]$Command,
  [ValidatePattern('^[0-9a-f]{32}$')][string]$ChoiceId,
  [ValidateSet('on','off')][string]$Value
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
if ($Command -eq 'select' -and -not $ChoiceId) { throw 'select requires -ChoiceId from catalog.' }
if ($Command -in @('enable','wait') -and -not $Value) { throw "$Command requires -Value on or off." }
if ($Command -notin @('enable','wait') -and $Value) { throw '-Value is only for enable and wait.' }
if ($Command -ne 'select' -and $ChoiceId) { throw '-ChoiceId is only for select.' }

$adb = (Get-Command adb -ErrorAction Stop).Source
$uri = 'content://io.github.mesmerprism.questautobootexample.operator'
$arguments = @('-s', $Serial, 'shell', 'content', 'call', '--uri', $uri, '--method', $Command)
switch ($Command) {
  'select' { $arguments += @('--extra', "choice_id:s:$ChoiceId") }
  'enable' { $arguments += @('--extra', "enabled:b:$($Value -eq 'on')".ToLowerInvariant()) }
  'wait' { $arguments += @('--extra', "wait_for_wearer:b:$($Value -eq 'on')".ToLowerInvariant()) }
}
$raw = (& $adb @arguments 2>&1 | Out-String).Trim()
if ($LASTEXITCODE -ne 0 -or $raw -notmatch 'Result:\s*Bundle\[' -or
    $raw -notmatch 'ok=(true|false)' -or $raw -notmatch 'payload_b64=([A-Za-z0-9+/=]+)') {
  throw 'Typed operator did not return a complete Bundle. Inspect the exact device/installation before retrying.'
}
$encoded = ([regex]::Match($raw, 'payload_b64=([A-Za-z0-9+/=]+)')).Groups[1].Value
$accepted = $raw -match 'ok=true'
$payload = [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($encoded))
$decoded = $payload | ConvertFrom-Json -AsHashtable
if (-not $accepted) { throw "Typed operator rejected request: $($decoded.error)" }
$decoded | ConvertTo-Json -Depth 10
