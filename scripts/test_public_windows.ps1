param([Parameter(Mandatory = $true)][string]$Bundle)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$launcher = Join-Path (Resolve-Path -LiteralPath $Bundle).Path 'knitty.exe'
$temporary = Join-Path ([IO.Path]::GetTempPath()) ('Knitty public smoke ' + [Guid]::NewGuid().ToString())
$previousConfig = $env:XDG_CONFIG_HOME
try {
    [IO.Directory]::CreateDirectory($temporary) | Out-Null
    $env:XDG_CONFIG_HOME = Join-Path $temporary 'config'
    $library = Join-Path $temporary 'Steam library with spaces'
    [IO.Directory]::CreateDirectory((Join-Path $library 'steamapps')) | Out-Null
    $marker = [Guid]::NewGuid().ToString() + '.txt'
    [IO.File]::WriteAllText((Join-Path $library $marker), 'fixture')
    Push-Location $temporary
    try {
        & $launcher '--help'
        if ($LASTEXITCODE -ne 0) { throw 'Native console launcher failed.' }
        & $launcher 'setup' '--steam-library' $library '--yes'
        if ($LASTEXITCODE -ne 0) { throw 'Native launcher lost arguments or could not save settings.' }
        $settings = Join-Path $env:XDG_CONFIG_HOME 'knitty\settings.json'
        if (!(Test-Path -LiteralPath $settings)) { throw 'Settings were not written to the user config directory.' }
        $saved = Get-Content -LiteralPath $settings -Raw | ConvertFrom-Json
        # Java canonicalizes Windows short paths, so verify the directory identity through the fixture.
        if (!(Test-Path -LiteralPath (Join-Path $saved.steamLibrary $marker))) {
            throw 'Path with spaces did not resolve to the selected Steam library.'
        }
        & $launcher 'not-a-command'
        if ($LASTEXITCODE -eq 0) { throw 'Native launcher did not propagate a failure exit code.' }
    } finally { Pop-Location }
    Write-Host 'Public Windows launcher smoke tests passed.'
} finally {
    $env:XDG_CONFIG_HOME = $previousConfig
    if (Test-Path -LiteralPath $temporary) { Remove-Item -LiteralPath $temporary -Recurse -Force }
}
exit 0
