[CmdletBinding()]
param(
  [ValidateRange(1024, 65535)]
  [int]$Port = 8767
)

$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path -LiteralPath (Split-Path -Parent $PSScriptRoot)).Path
$python = Get-Command python -ErrorAction Stop
$previewUrl = "http://127.0.0.1:$Port/tools/rusty-kiosk-panel-browser-preview/"

Write-Output "Rusty Kiosk panel preview: $previewUrl"
Write-Output 'Press Ctrl+C to stop the local server.'

Push-Location $repoRoot
try {
  & $python.Source -m http.server $Port --bind 127.0.0.1
  if ($LASTEXITCODE -ne 0) { throw "Preview server stopped with exit code $LASTEXITCODE." }
} finally {
  Pop-Location
}
