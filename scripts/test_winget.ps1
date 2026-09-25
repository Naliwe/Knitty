param(
    [Parameter(Mandatory = $true)][string]$Manifest,
    [string]$Archive,
    [switch]$AllowArchiveScanOverride
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$manifestDirectory = (Resolve-Path -LiteralPath $Manifest).Path
$links = Join-Path $env:LOCALAPPDATA 'Microsoft\WinGet\Links'
$alias = Join-Path $links 'knitty.exe'

if ($AllowArchiveScanOverride) {
    if (!$Archive) { throw 'The archive scan exception requires the reviewed release ZIP.' }
    $archivePath = (Resolve-Path -LiteralPath $Archive).Path
    $reviewedHash = '7ADB2EF915C154A8299CD371E6521CFD16E9E02A875ACDA9043425C3E03B4469'
    if ((Get-FileHash -LiteralPath $archivePath -Algorithm SHA256).Hash -ne $reviewedHash) {
        throw 'The local archive scan exception applies only to the reviewed v0.7.0 Windows ZIP.'
    }

    # Pure rejects this JAR's empty DEFLATE directories and nested entry count.
    # This exception does not disable Defender or WinGet's installer hash check.
    $defender = Get-MpComputerStatus
    if (!$defender.AMServiceEnabled -or !$defender.AntivirusEnabled) {
        throw 'Defender must be enabled before testing the reviewed archive exception.'
    }
    Start-MpScan -ScanType CustomScan -ScanPath $archivePath
    if (@(Get-MpThreatDetection).Count -ne 0) {
        throw 'Defender reported a threat; the archive exception will not be used.'
    }
}

winget settings --enable LocalManifestFiles
if ($LASTEXITCODE -ne 0) { throw 'Could not enable local WinGet manifests; run from an elevated terminal.' }

winget validate --manifest $manifestDirectory
if ($LASTEXITCODE -ne 0) { throw 'WinGet manifest validation failed.' }

# Run on a disposable Windows machine: this installs and removes Naliwe.Knitty.
$installed = $false
$overrideEnabled = $false
try {
    $installArguments = @(
        'install', '--manifest', $manifestDirectory, '--scope', 'user',
        '--silent', '--accept-source-agreements', '--disable-interactivity'
    )
    if ($AllowArchiveScanOverride) {
        winget settings --enable LocalArchiveMalwareScanOverride
        if ($LASTEXITCODE -ne 0) { throw 'Could not enable the reviewed local archive exception.' }
        $overrideEnabled = $true
        $installArguments += '--ignore-local-archive-malware-scan'
    }

    winget @installArguments
    if ($LASTEXITCODE -ne 0) { throw 'WinGet installation failed.' }
    $installed = $true
    if (!(Test-Path -LiteralPath $alias)) { throw 'WinGet did not create the knitty command alias.' }

    # Exercise the alias, so a launcher that only works from its package directory cannot pass.
    & (Join-Path $PSScriptRoot 'test_public_windows.ps1') -Bundle $links
    if ($LASTEXITCODE -ne 0) { throw 'The installed WinGet command failed its smoke tests.' }

    winget list --id Naliwe.Knitty --exact --accept-source-agreements --disable-interactivity
    if ($LASTEXITCODE -ne 0) { throw 'WinGet did not register the installed package.' }
} finally {
    if ($overrideEnabled) {
        winget settings --disable LocalArchiveMalwareScanOverride
        if ($LASTEXITCODE -ne 0) { throw 'Could not disable the temporary local archive exception.' }
    }
    if ($installed) {
        winget uninstall --id Naliwe.Knitty --exact --silent --accept-source-agreements --disable-interactivity
        if ($LASTEXITCODE -ne 0) { throw 'WinGet uninstallation failed.' }
    }
}

if (Test-Path -LiteralPath $alias) { throw 'WinGet left the command alias after uninstalling.' }
Write-Host 'WinGet validation, installation, command alias, and uninstallation checks passed.'
