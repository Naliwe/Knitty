param([Parameter(Mandatory = $true)][string]$Manifest)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$manifestDirectory = (Resolve-Path -LiteralPath $Manifest).Path
$links = Join-Path $env:LOCALAPPDATA 'Microsoft\WinGet\Links'
$alias = Join-Path $links 'knitty.exe'

winget settings --enable LocalManifestFiles
if ($LASTEXITCODE -ne 0) { throw 'Could not enable local WinGet manifests; run from an elevated terminal.' }

winget validate --manifest $manifestDirectory
if ($LASTEXITCODE -ne 0) { throw 'WinGet manifest validation failed.' }

# Run on a disposable Windows machine: this installs and removes Naliwe.Knitty.
$installed = $false
try {
    winget install --manifest $manifestDirectory --scope user --silent --accept-source-agreements --disable-interactivity
    if ($LASTEXITCODE -ne 0) { throw 'WinGet installation failed.' }
    $installed = $true
    if (!(Test-Path -LiteralPath $alias)) { throw 'WinGet did not create the knitty command alias.' }

    # Exercise the alias, so a launcher that only works from its package directory cannot pass.
    & (Join-Path $PSScriptRoot 'test_public_windows.ps1') -Bundle $links
    if ($LASTEXITCODE -ne 0) { throw 'The installed WinGet command failed its smoke tests.' }

    winget list --id Naliwe.Knitty --exact --accept-source-agreements --disable-interactivity
    if ($LASTEXITCODE -ne 0) { throw 'WinGet did not register the installed package.' }
} finally {
    if ($installed) {
        winget uninstall --id Naliwe.Knitty --exact --silent --accept-source-agreements --disable-interactivity
        if ($LASTEXITCODE -ne 0) { throw 'WinGet uninstallation failed.' }
    }
}

if (Test-Path -LiteralPath $alias) { throw 'WinGet left the command alias after uninstalling.' }
Write-Host 'WinGet validation, installation, command alias, and uninstallation checks passed.'
