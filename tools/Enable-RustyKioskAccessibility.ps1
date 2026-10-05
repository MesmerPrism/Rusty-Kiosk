[CmdletBinding()]
param(
  [Parameter(Mandatory = $true)]
  [string]$Serial,

  [ValidateSet('stable', 'labs')]
  [string]$ProductChannel = 'stable',

  [string]$AdbPath = 'adb'
)

$ErrorActionPreference = 'Stop'
$appPackage = if ($ProductChannel -eq 'labs') { 'io.github.mesmerprism.rustykiosk.labs' } else { 'io.github.mesmerprism.rustykiosk' }
$component = "$appPackage/io.github.mesmerprism.rustykiosk.KioskAccessibilityService"
$adbExecutable = $AdbPath
$restrictedSettingsOp = 'ACCESS_RESTRICTED_SETTINGS'

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

$installedPath = Invoke-SerialAdb -CommandParts @('shell', 'pm', 'path', $appPackage)
if ($installedPath -notmatch '^package:') {
  throw "Install Rusty Kiosk $ProductChannel before enabling its Accessibility service."
}

Invoke-SerialAdb -CommandParts @(
  'shell', 'cmd', 'appops', 'set', $appPackage, $restrictedSettingsOp, 'allow'
) | Out-Null
$restrictedSettingsState = Invoke-SerialAdb -CommandParts @(
  'shell', 'cmd', 'appops', 'get', $appPackage, $restrictedSettingsOp
)
if ($restrictedSettingsState -notmatch 'ACCESS_RESTRICTED_SETTINGS:\s+allow') {
  throw 'Android restricted-settings authorization did not read back as allowed.'
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

Write-Output "Rusty Kiosk $ProductChannel Accessibility service is enabled; existing services were preserved."
