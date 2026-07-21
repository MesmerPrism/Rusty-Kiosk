[CmdletBinding()]
param(
  [Parameter(Mandatory)]
  [string]$Serial,

  [ValidateRange(1, 3)]
  [int]$Count = 1,

  [ValidateRange(1200, 5000)]
  [int]$IntervalMilliseconds = 1400,

  [ValidateRange(250, 10000)]
  [int]$SettleMilliseconds = 2500
)

$ErrorActionPreference = 'Stop'
$adbPath = (Get-Command adb -ErrorAction Stop).Source
$kioskPackage = 'io.github.mesmerprism.rustykiosk'
$state = (& $adbPath -s $Serial get-state 2>&1).Trim()
if ($LASTEXITCODE -ne 0 -or $state -ne 'device') {
  throw "ADB device '$Serial' is not reachable."
}

function Get-ResumedActivities {
  @(
    & $adbPath -s $Serial shell dumpsys activity activities |
    Select-String 'topResumedActivity=' |
    Select-Object -First 12 |
    ForEach-Object { $_.Line.Trim() }
  )
}

$resolvedHome =
  & $adbPath -s $Serial shell cmd package resolve-activity --brief `
    -a android.intent.action.MAIN `
    -c android.intent.category.HOME |
    Where-Object { $_ -match '/' } |
    Select-Object -Last 1
if ([string]::IsNullOrWhiteSpace($resolvedHome)) {
  throw 'Android did not resolve a MAIN + HOME activity.'
}
$resolvedHome = $resolvedHome.Trim()
$beforeActivities = Get-ResumedActivities

for ($index = 1; $index -le $Count; $index++) {
  $startOutput =
    & $adbPath -s $Serial shell am start `
      -a android.intent.action.MAIN `
      -c android.intent.category.HOME 2>&1
  if ($LASTEXITCODE -ne 0 -or ($startOutput -join "`n") -match 'SecurityException|Error:') {
    throw "Android HOME activity launch $index failed: $($startOutput -join ' ')"
  }
  if ($index -lt $Count) {
    Start-Sleep -Milliseconds $IntervalMilliseconds
  }
}

Start-Sleep -Milliseconds $SettleMilliseconds
$afterActivities = Get-ResumedActivities
$guardXml =
  (& $adbPath -s $Serial exec-out run-as $kioskPackage cat `
    shared_prefs/rusty_kiosk_guard_state.xml 2>$null) -join "`n"
$guardArmedAfter = $null
if ($LASTEXITCODE -eq 0 -and
    $guardXml -match '<boolean name="armed" value="(true|false)"') {
  $guardArmedAfter = $Matches[1] -eq 'true'
}

[ordered]@{
  schema = 'rusty.kiosk.system_home_result.v1'
  provider = 'android-home-activity'
  resolved_home = $resolvedHome
  requested_home_count = $Count
  interval_ms = if ($Count -gt 1) { $IntervalMilliseconds } else { 0 }
  guard_armed_after = $guardArmedAfter
  home_observed = [bool]($afterActivities -match [Regex]::Escape($resolvedHome))
  before = $beforeActivities | Select-Object -First 1
  after = $afterActivities | Select-Object -First 1
  resumed_before = $beforeActivities
  resumed_after = $afterActivities
  settled_ms = $SettleMilliseconds
} | ConvertTo-Json
