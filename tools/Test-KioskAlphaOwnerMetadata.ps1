[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$MetadataPath,
    [Parameter(Mandatory = $true)][string]$ExpectedTag,
    [Parameter(Mandatory = $true)][string]$ExpectedVersion,
    [Parameter(Mandatory = $true)][string]$ExpectedSourceRevision,
    [Parameter(Mandatory = $true)][string]$ExpectedSourceTree,
    [Parameter(Mandatory = $true)][string]$PrimaryArtifactPath,
    [Parameter(Mandatory = $true)][string]$BundleManifestPath
)

$ErrorActionPreference = 'Stop'
Import-Module (Join-Path $PSScriptRoot 'RustyKiosk.AlphaOwnerMetadata.psm1') -Force
Assert-RustyKioskAlphaOwnerMetadata @PSBoundParameters
Write-Output 'Kiosk alpha owner metadata passed strict validation.'
