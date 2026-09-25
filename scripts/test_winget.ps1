param(
    [Parameter(Mandatory = $true)][string]$Manifest,
    [string]$Archive,
    [switch]$AllowArchiveScanOverride
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$manifestDirectory = (Resolve-Path -LiteralPath $Manifest).Path

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

    # WinGet updates the persisted user PATH, which an existing shell has not inherited yet.
    $userPath = [Environment]::GetEnvironmentVariable('Path', 'User')
    $env:PATH = "$env:PATH;$userPath"
    $launcher = Get-Command knitty -CommandType Application -ErrorAction Stop
    $launcherDirectory = [IO.Path]::GetDirectoryName($launcher.Source)
    if ($userPath.Split(';') -notcontains $launcherDirectory) {
        throw 'WinGet did not add the launcher directory to the user PATH.'
    }

    knitty --help
    if ($LASTEXITCODE -ne 0) { throw 'The installed knitty command failed through PATH.' }
    & (Join-Path $PSScriptRoot 'test_public_windows.ps1') -Bundle $launcherDirectory
    if ($LASTEXITCODE -ne 0) { throw 'The installed WinGet command failed its smoke tests.' }
} finally {
    if ($overrideEnabled) {
        winget settings --disable LocalArchiveMalwareScanOverride
        if ($LASTEXITCODE -ne 0) { throw 'Could not disable the temporary local archive exception.' }
    }
    if ($installed) {
        winget uninstall --manifest $manifestDirectory --silent --accept-source-agreements --disable-interactivity
        if ($LASTEXITCODE -ne 0) { throw 'WinGet uninstallation failed.' }
    }
}

if (Test-Path -LiteralPath $launcher.Source) { throw 'WinGet left the executable after uninstalling.' }
$remainingUserPath = [Environment]::GetEnvironmentVariable('Path', 'User')
if ($remainingUserPath.Split(';') -contains $launcherDirectory) {
    throw 'WinGet left the launcher directory on the user PATH after uninstalling.'
}
Write-Host 'WinGet validation, installation, PATH command, and uninstallation checks passed.'
