[CmdletBinding()]
param(
  [Parameter(Mandatory)]
  [string]$Serial,

  [ValidateRange(1, 3)]
  [int]$Count = 1,

  [ValidateRange(1200, 5000)]
  [int]$IntervalMilliseconds = 1400,

  [ValidateRange(3, 30)]
  [int]$TimeoutSeconds = 12
)

$ErrorActionPreference = 'Stop'
$adbPath = (Get-Command adb -ErrorAction Stop).Source
$packageName = 'io.github.mesmerprism.rustykiosk'
$receiver = "$packageName/.RustyKioskGuardCliReceiver"
$action = "$packageName.debug.action.GUARD_HOME_TRANSITION"
$resultPath = 'files/cli/guard-last-result.json'

$state = (& $adbPath -s $Serial get-state 2>&1).Trim()
if ($LASTEXITCODE -ne 0 -or $state -ne 'device') {
  throw "ADB device '$Serial' is not reachable."
}

$results = @()
for ($index = 1; $index -le $Count; $index++) {
  $requestId = [Guid]::NewGuid().ToString('N')
  $dispatch =
    & $adbPath -s $Serial shell am broadcast `
      -n $receiver `
      -a $action `
      --es rusty_kiosk_guard_cli_request_id $requestId 2>&1
  if ($LASTEXITCODE -ne 0 -or ($dispatch -join "`n") -match 'SecurityException|Permission Denial|Error:') {
    throw "Guard CLI dispatch $index failed: $($dispatch -join ' ')"
  }

  $deadline = [DateTimeOffset]::UtcNow.AddSeconds($TimeoutSeconds)
  $matched = $null
  do {
    $jsonText =
      (& $adbPath -s $Serial exec-out run-as $packageName cat $resultPath 2>$null) -join "`n"
    if ($LASTEXITCODE -eq 0 -and -not [string]::IsNullOrWhiteSpace($jsonText)) {
      try {
        $candidate = $jsonText | ConvertFrom-Json
        if ($candidate.request_id -eq $requestId) {
          $matched = $candidate
          break
        }
      } catch {
        # The service may be atomically replacing the result file.
      }
    }
    Start-Sleep -Milliseconds 100
  } while ([DateTimeOffset]::UtcNow -lt $deadline)

  if ($null -eq $matched) {
    throw "Guard CLI timed out waiting for request '$requestId'."
  }
  $results += $matched
  if ($index -lt $Count) {
    Start-Sleep -Milliseconds $IntervalMilliseconds
  }
}

[ordered]@{
  schema = 'rusty.kiosk.guard_cli_sequence.v1'
  requested_count = $Count
  interval_ms = if ($Count -gt 1) { $IntervalMilliseconds } else { 0 }
  transitions = $results
} | ConvertTo-Json -Depth 8
