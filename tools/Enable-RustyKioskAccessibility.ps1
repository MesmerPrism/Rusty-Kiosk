[CmdletBinding()]
param(
  [Parameter(Mandatory = $true)]
  [string]$Serial,

  [string]$AdbPath = 'adb'
)

$ErrorActionPreference = 'Stop'
$component = 'io.github.mesmerprism.rustykiosk/io.github.mesmerprism.rustykiosk.KioskAccessibilityService'
$adbExecutable = $AdbPath

function Invoke-SerialAdb {
  param([Parameter(Mandatory = $true)][string[]]$CommandParts)

  $output = & $adbExecutable -s $Serial @CommandParts
  if ($LASTEXITCODE -ne 0) {
    throw "ADB command failed: $($CommandParts -join ' ')"
  }
  return ($output -join "`n").Trim()
}

$deviceState = Invoke-SerialAdb -CommandParts @('get-state')
if ($deviceState -ne 'device') {
  throw "Quest '$Serial' is not in the ADB device state."
}

$installedPath = Invoke-SerialAdb -CommandParts @('shell', 'pm', 'path', 'io.github.mesmerprism.rustykiosk')
if ($installedPath -notmatch '^package:') {
  throw 'Install Rusty Kiosk before enabling its Accessibility service.'
}

$existing = Invoke-SerialAdb -CommandParts @('shell', 'settings', 'get', 'secure', 'enabled_accessibility_services')
$enabled =
  @($existing -split ':' | Where-Object { $_ -and $_ -ne 'null' }) + $component |
    Select-Object -Unique
$joined = $enabled -join ':'

Invoke-SerialAdb -CommandParts @('shell', 'settings', 'put', 'secure', 'enabled_accessibility_services', $joined) | Out-Null
Invoke-SerialAdb -CommandParts @('shell', 'settings', 'put', 'secure', 'accessibility_enabled', '1') | Out-Null

$readback = Invoke-SerialAdb -CommandParts @('shell', 'settings', 'get', 'secure', 'enabled_accessibility_services')
$flagReadback = Invoke-SerialAdb -CommandParts @('shell', 'settings', 'get', 'secure', 'accessibility_enabled')
if ($component -notin @($readback -split ':')) {
  throw 'Accessibility component readback does not include Rusty Kiosk.'
}
if ($flagReadback -ne '1') {
  throw 'Global Accessibility enabled flag did not read back as 1.'
}

Write-Output 'Rusty Kiosk Accessibility service is enabled; existing services were preserved.'
