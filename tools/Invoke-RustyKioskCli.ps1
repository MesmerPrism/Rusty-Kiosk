[CmdletBinding()]
param(
  [Parameter(Mandatory)]
  [string]$Serial,

  [Parameter(Mandatory)]
  [ValidateSet(
    'status',
    'show-controls',
    'show-apps',
    'reload',
    'focus-search',
    'focus-tag-editor',
    'set-search',
    'select',
    'filter-tag',
    'add-tag',
    'remove-tag',
    'set-launch-requirement',
    'cancel-pending-launch',
    'launch-normal',
    'launch-kiosk',
    'launch-option',
    'check-setup-helper',
    'request-wifi-adb',
    'enable-wifi-adb-after-boot',
    'disable-wifi-adb-after-boot',
    'disable-wifi-adb',
    'enable-accessibility',
    'disable-accessibility',
    'passthrough-natural',
    'passthrough-contour',
    'exit-meta-home'
  )]
  [string]$Command,

  [string]$Value,

  [ValidateRange(3, 30)]
  [int]$TimeoutSeconds = 15
)

$ErrorActionPreference = 'Stop'
$packageName = 'io.github.mesmerprism.rustykiosk'
$cliActivity = "$packageName/.RustyKioskCliActivity"
$resultPath = 'files/cli/last-result.json'
$adbPath = (Get-Command adb -ErrorAction Stop).Source
$requestId = [Guid]::NewGuid().ToString('N')

$valueCommands = @('set-search', 'select', 'filter-tag', 'add-tag', 'remove-tag', 'set-launch-requirement', 'launch-option')
$requiredValueCommands = @('select', 'add-tag', 'remove-tag', 'set-launch-requirement', 'launch-option')
if ($Command -notin $valueCommands -and $PSBoundParameters.ContainsKey('Value')) {
  throw "Command '$Command' does not accept -Value."
}
if ($Command -in $requiredValueCommands -and [string]::IsNullOrWhiteSpace($Value)) {
  throw "Command '$Command' requires -Value."
}
if ($Command -eq 'set-launch-requirement' -and $Value -notin @('any', 'wifi-on', 'wifi-off')) {
  throw "Command 'set-launch-requirement' requires exactly any, wifi-on, or wifi-off."
}

$state = (& $adbPath -s $Serial get-state 2>&1).Trim()
if ($LASTEXITCODE -ne 0 -or $state -ne 'device') {
  throw "ADB device '$Serial' is not reachable."
}

$startArguments = @(
  '-s', $Serial,
  'shell', 'am', 'start',
  '-n', $cliActivity,
  '--es', 'rusty_kiosk_cli_request_id', $requestId,
  '--es', 'rusty_kiosk_cli_command', $Command
)
if ($PSBoundParameters.ContainsKey('Value') -and -not [string]::IsNullOrEmpty($Value)) {
  $encodedValue = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($Value))
  $startArguments += @('--es', 'rusty_kiosk_cli_value_base64', $encodedValue)
}

$startOutput = & $adbPath @startArguments 2>&1
if ($LASTEXITCODE -ne 0 -or ($startOutput -join "`n") -match 'SecurityException|Permission Denial|Error:') {
  throw "Rusty Kiosk CLI dispatch failed: $($startOutput -join ' ')"
}

$deadline = [DateTimeOffset]::UtcNow.AddSeconds($TimeoutSeconds)
do {
  $jsonText = (& $adbPath -s $Serial exec-out run-as $packageName cat $resultPath 2>$null) -join "`n"
  if ($LASTEXITCODE -eq 0 -and -not [string]::IsNullOrWhiteSpace($jsonText)) {
    try {
      $result = $jsonText | ConvertFrom-Json
      if ($result.request_id -eq $requestId) {
        $result | ConvertTo-Json -Depth 12
        exit 0
      }
    } catch {
      # The app may not have created its first result file yet.
    }
  }
  Start-Sleep -Milliseconds 200
} while ([DateTimeOffset]::UtcNow -lt $deadline)

throw "Rusty Kiosk CLI timed out waiting for request '$requestId'."
