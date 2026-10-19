# SPDX-License-Identifier: AGPL-3.0-or-later
# Pure validation only. This file never invokes ADB or changes a device.
Set-StrictMode -Version Latest

function Get-CompanionQuarantineAdbArguments([string]$Serial, [string]$Command) {
  # exec-out preserves receipt bytes; shell -T can still translate LF to CRLF on this platform.
  return @('-s', $Serial, 'exec-out', $Command)
}

function Assert-CompanionUuid([string]$Value) {
  $parsed = [Guid]::Empty
  if (-not [Guid]::TryParseExact($Value, 'D', [ref]$parsed) -or $parsed.ToString() -cne $Value) {
    throw 'Canonical lowercase UUID required.'
  }
}

function ConvertFrom-CompanionReceipt([string]$Text) {
  $doc = [System.Text.Json.JsonDocument]::Parse($Text)
  try {
    if ($doc.RootElement.ValueKind -ne [System.Text.Json.JsonValueKind]::Object) { throw 'Receipt must be an object.' }
    $names = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    foreach ($property in $doc.RootElement.EnumerateObject()) {
      if (-not $names.Add($property.Name)) { throw 'Duplicate JSON field.' }
    }
  } finally { $doc.Dispose() }
  return ConvertFrom-Json -InputObject $Text -AsHashtable
}

function Assert-CompanionFields($Value, [string[]]$Fields) {
  if ($Value.get_Count() -ne $Fields.Count) { throw 'Unexpected receipt fields.' }
  foreach ($key in $Fields) { if (-not $Value.Contains($key)) { throw "Missing receipt field: $key" } }
}

function Test-CompanionInteger($Value) {
  return $Value -is [int] -or $Value -is [long] -or $Value -is [uint32] -or $Value -is [uint64]
}

function Test-CompanionPostbootQuarantine {
  param(
    [string]$Session, [string]$ExpectedPriorBoot, [string]$ExpectedCurrentBoot,
    [string]$ExpectedSourceId, [string]$ProductChannel = 'stable',
    [string]$OwnerText, [string[]]$StateNames, [System.Collections.IDictionary]$StateTexts,
    [string[]]$JournalTexts
  )
  foreach ($id in @($Session, $ExpectedPriorBoot, $ExpectedCurrentBoot)) { Assert-CompanionUuid $id }
  if ($ExpectedPriorBoot -ceq $ExpectedCurrentBoot) { throw 'Same-boot quarantine is forbidden.' }
  if ($ExpectedSourceId -cnotmatch '^[0-9a-f]{64}$' -or $ProductChannel -cnotin @('stable','labs')) { throw 'Exact source/channel required.' }
  $owner = ConvertFrom-CompanionReceipt $OwnerText
  Assert-CompanionFields $owner @('session', 'boot', 'pid', 'start_ticks', 'source_id', 'product_channel')
  if ($owner.session -cne $Session -or $owner.boot -cne $ExpectedPriorBoot -or
      -not (Test-CompanionInteger $owner.pid) -or $owner.pid -le 0 -or $owner.pid -gt [int]::MaxValue -or
      $owner.start_ticks -isnot [string] -or $owner.start_ticks -notmatch '^[0-9]+$' -or
      $owner.source_id -cne $ExpectedSourceId -or $owner.product_channel -cne $ProductChannel) {
    throw 'Owner session/prior boot/process birth mismatch.'
  }
  $names = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
  $intents = @{}; $results = @{}
  $capabilityIntent = $null; $capabilityResult = $null
  foreach ($name in $StateNames) {
    if ($name -cin @('CAPABILITY-INTENT.json', 'CAPABILITY-RESULT.json')) {
      if (-not $names.Add($name) -or -not $StateTexts.Contains($name)) { throw 'Duplicate or missing capability receipt.' }
      $value = ConvertFrom-CompanionReceipt $StateTexts[$name]
      $common = @('schema','session','boot','pid','function','query','dispatch_uptime_ms')
      Assert-CompanionFields $value $(if ($name -ceq 'CAPABILITY-INTENT.json') { $common } else { $common + @('callback_uptime_ms','enabled','error') })
      if ($value.schema -cne 'rusty.kiosk.shell_companion.capability.v1' -or $value.session -cne $Session -or
          $value.boot -cne $ExpectedPriorBoot -or $value.pid -ne $owner.pid -or
          $value.function -cne 'com.oculus.vrshell.appfunctions.ShellAppFunctions#openNavigator' -or $value.query -cne 'is-enabled' -or
          -not (Test-CompanionInteger $value.dispatch_uptime_ms) -or $value.dispatch_uptime_ms -lt 0) { throw 'Capability original identity mismatch.' }
      if ($name -ceq 'CAPABILITY-INTENT.json') { $capabilityIntent = $value } else {
        if (-not (Test-CompanionInteger $value.callback_uptime_ms) -or $value.callback_uptime_ms -lt $value.dispatch_uptime_ms -or
            ($null -ne $value.enabled -and $value.enabled -isnot [bool]) -or
            ($null -ne $value.error -and $value.error -isnot [string]) -or
            ($null -eq $value.enabled -and [string]::IsNullOrEmpty($value.error))) { throw 'Malformed capability callback.' }
        $capabilityResult = $value
      }
      continue
    }
    if (-not $names.Add($name) -or $name -cnotmatch '^(INTENT|RESULT)-([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})\.json$') {
      throw 'Duplicate or unexpected state filename.'
    }
    $kind = $Matches[1]; $id = $Matches[2]; Assert-CompanionUuid $id
    if (-not $StateTexts.Contains($name)) { throw 'Missing state file body.' }
    $value = ConvertFrom-CompanionReceipt $StateTexts[$name]
    if ($value.command_id -cne $id) { throw 'Filename/command identity mismatch.' }
    if ($kind -ceq 'INTENT') {
      Assert-CompanionFields $value @('command_id','generation','source_uptime_ms','due_uptime_ms','function','parameters')
      if (-not (Test-CompanionInteger $value.generation) -or $value.generation -eq 0 -or
          -not (Test-CompanionInteger $value.source_uptime_ms) -or $value.source_uptime_ms -lt 0 -or
          -not (Test-CompanionInteger $value.due_uptime_ms) -or $value.due_uptime_ms - $value.source_uptime_ms -ne 80 -or
          $value.function -cne 'com.oculus.vrshell.appfunctions.ShellAppFunctions#openNavigator' -or $value.parameters -cne 'empty') {
        throw 'Unexpected original intent.'
      }
      $intents[$id] = $value
    } else {
      Assert-CompanionFields $value @('command_id','outcome','success','dispatch_uptime_ms','callback_uptime_ms','callback_error','effect_verified')
      if ($value.outcome -cne 'callback' -or $value.success -isnot [bool] -or
          -not (Test-CompanionInteger $value.dispatch_uptime_ms) -or $value.dispatch_uptime_ms -lt 0 -or
          -not (Test-CompanionInteger $value.callback_uptime_ms) -or $value.callback_uptime_ms -lt $value.dispatch_uptime_ms -or
          ($null -ne $value.callback_error -and $value.callback_error -isnot [string]) -or
          $value.effect_verified -isnot [bool] -or $value.effect_verified) { throw 'Unknown or malformed original result.' }
      $results[$id] = $value
    }
  }
  if ($StateTexts.Count -ne $names.Count -or $intents.Count -ne $results.Count) { throw 'Original/result inventory mismatch.' }
  if ($null -eq $capabilityIntent -or $null -eq $capabilityResult -or
      $capabilityIntent.dispatch_uptime_ms -ne $capabilityResult.dispatch_uptime_ms) { throw 'Capability read original remains unresolved.' }
  $dispatches = @{}; $callbacks = @{}; $withdrawalSeen = $false
  foreach ($text in $JournalTexts) {
    foreach ($line in ($text -split '\r?\n')) {
      if ([string]::IsNullOrWhiteSpace($line)) { continue }
      $entry = ConvertFrom-CompanionReceipt $line
      if ($entry.session -cne $Session -or $entry.pid -ne $owner.pid -or
          $entry.schema -cne 'rusty-kiosk.shell-companion.v1') { throw 'Journal owner mismatch.' }
      if ($entry.event -ceq 'WITHDRAWAL_UNKNOWN_HELD') { $withdrawalSeen = $true }
      if ($entry.event -cin @('API_DISPATCH_INTENT','CALLBACK_KNOWN')) {
        $id = $entry.command_id; Assert-CompanionUuid $id
        $table = if ($entry.event -ceq 'API_DISPATCH_INTENT') { $dispatches } else { $callbacks }
        if ($table.Contains($id)) { throw 'Duplicate original journal phase.' }
        $table[$id] = $entry
      }
    }
  }
  if (-not $withdrawalSeen) { throw 'This operation requires the recorded prior withdrawal hold.' }
  if ($dispatches.Count -ne $intents.Count -or $callbacks.Count -ne $intents.Count) { throw 'Missing or extra original journal phases.' }
  foreach ($id in $intents.Keys) {
    if (-not $results.Contains($id) -or -not $dispatches.Contains($id) -or -not $callbacks.Contains($id)) { throw 'Unresolved original.' }
    $intent = $intents[$id]; $result = $results[$id]; $dispatch = $dispatches[$id]; $callback = $callbacks[$id]
    if ($result.dispatch_uptime_ms -lt $intent.due_uptime_ms -or
        $dispatch.generation -ne $intent.generation -or $dispatch.source_uptime_ms -ne $intent.source_uptime_ms -or
        $dispatch.dispatch_uptime_ms -ne $result.dispatch_uptime_ms -or
        $callback.dispatch_uptime_ms -ne $result.dispatch_uptime_ms -or
        $callback.callback_uptime_ms -ne $result.callback_uptime_ms -or
        $callback.success -isnot [bool] -or $callback.success -ne $result.success -or
        $callback.effect_verified -isnot [bool] -or $callback.effect_verified) { throw 'Original journal join mismatch.' }
  }
  return [ordered]@{ prior_owner = $owner; original_count = $intents.Count; journal_originals_joined = $callbacks.Count; capability_read_joined = $true }
}
