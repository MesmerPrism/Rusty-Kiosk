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
Import-Module (Join-Path $PSScriptRoot 'RustyKiosk.LabsOwnerMetadata.psm1') -Force
Assert-RustyKioskLabsOwnerMetadata @PSBoundParameters
Write-Output 'Kiosk Labs owner metadata passed strict validation.'
