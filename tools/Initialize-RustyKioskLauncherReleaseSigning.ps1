[CmdletBinding()]
param(
  [string]$OutputDir,
  [string]$Alias = 'rusty-kiosk-launcher-release',
  [string]$DName = 'CN=Rusty Kiosk Launcher, O=MesmerPrism',
  [switch]$Force
)

$ErrorActionPreference = 'Stop'

if ($PSVersionTable.PSEdition -ne 'Core' -or $PSVersionTable.PSVersion -lt [version]'7.6') {
  throw 'Release-signing setup requires PowerShell 7.6 or newer through pwsh.'
}

$repoRoot = Split-Path -Parent $PSScriptRoot
if ([string]::IsNullOrWhiteSpace($OutputDir)) {
  $OutputDir = Join-Path $repoRoot 'local-artifacts\signing\rusty-kiosk-launcher'
}

$keytool = Get-Command keytool -ErrorAction SilentlyContinue
if ($null -eq $keytool) {
  throw 'keytool was not found. Configure JDK 17 before initializing release signing.'
}

function New-Secret {
  $bytes = [byte[]]::new(32)
  [Security.Cryptography.RandomNumberGenerator]::Fill($bytes)
  [Convert]::ToBase64String($bytes).TrimEnd('=')
}

function ConvertTo-SingleQuotedLiteral {
  param([Parameter(Mandatory = $true)][string]$Value)

  "'" + $Value.Replace("'", "''") + "'"
}

New-Item -ItemType Directory -Force -Path $OutputDir | Out-Null
$keystorePath = Join-Path $OutputDir 'rusty-kiosk-launcher-release.p12'
$loaderPath = Join-Path $OutputDir 'Load-RustyKioskLauncherReleaseSigning.ps1'

foreach ($path in @($keystorePath, $loaderPath)) {
  if ((Test-Path -LiteralPath $path) -and -not $Force) {
    throw "Release-signing file already exists: $path. Use -Force only for an intentional pre-upload replacement."
  }
}

$storePassword = New-Secret
$keyPassword = $storePassword
& $keytool.Source `
  -genkeypair `
  -storetype PKCS12 `
  -keystore $keystorePath `
  -storepass $storePassword `
  -keypass $keyPassword `
  -alias $Alias `
  -keyalg RSA `
  -keysize 4096 `
  -validity 9125 `
  -dname $DName `
  -noprompt
if ($LASTEXITCODE -ne 0) {
  throw "keytool failed with exit code $LASTEXITCODE."
}

$loader = @(
  (
    '$env:RUSTY_KIOSK_LAUNCHER_KEYSTORE_PATH = ' +
      (ConvertTo-SingleQuotedLiteral -Value ([IO.Path]::GetFullPath($keystorePath)))
  )
  (
    '$env:RUSTY_KIOSK_LAUNCHER_KEYSTORE_PASSWORD = ' +
      (ConvertTo-SingleQuotedLiteral -Value $storePassword)
  )
  (
    '$env:RUSTY_KIOSK_LAUNCHER_KEY_ALIAS = ' +
      (ConvertTo-SingleQuotedLiteral -Value $Alias)
  )
  (
    '$env:RUSTY_KIOSK_LAUNCHER_KEY_PASSWORD = ' +
      (ConvertTo-SingleQuotedLiteral -Value $keyPassword)
  )
)
[IO.File]::WriteAllLines($loaderPath, $loader, [Text.UTF8Encoding]::new($false))

[pscustomobject]@{
  status = 'created'
  keystore = [IO.Path]::GetFullPath($keystorePath)
  loader = [IO.Path]::GetFullPath($loaderPath)
  alias = $Alias
  secrets_printed = $false
} | ConvertTo-Json -Depth 4
