param([Parameter(Mandatory = $true)][string]$Bundle)

# Run on Windows after extracting the ZIP. All writes stay in a temporary fixture.
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
if ($env:OS -ne 'Windows_NT') { throw 'This smoke test requires Windows.' }
if ([Console]::IsOutputRedirected -or [Console]::IsInputRedirected) {
    throw 'Run in an interactive Windows console, without piping output: terminal initialization must be exercised.'
}
$Bundle = (Resolve-Path -LiteralPath $Bundle).Path
foreach ($name in @('credentials.ps1', 'launch.ps1')) {
    $tokens = $null
    $errors = $null
    [Management.Automation.Language.Parser]::ParseFile((Join-Path $Bundle $name), [ref]$tokens, [ref]$errors) | Out-Null
    if ($errors.Count -ne 0) { throw "PowerShell syntax errors in $name" }
}
. (Join-Path $Bundle 'credentials.ps1')
$temporary = Join-Path ([IO.Path]::GetTempPath()) ('Knitty smoke test ' + [Guid]::NewGuid().ToString())
$previousConfig = $env:XDG_CONFIG_HOME
try {
    [IO.Directory]::CreateDirectory($temporary) | Out-Null
    $file = Join-Path $temporary 'credentials\modio.json'
    foreach ($secret in @('test-key-one', 'test-key-two')) {
        $key = ConvertTo-SecureString $secret -AsPlainText -Force
        try {
            Save-KnittyCredentials -File $file -ApiPath 'https://u-123.modapi.io/v1' -ApiKey $key
            $loaded = Read-KnittyCredentials -File $file
            try {
                if ($loaded.GetNetworkCredential().Password -cne $secret) { throw 'Credential round-trip failed.' }
                if ((Get-Content -LiteralPath $file -Raw).Contains($secret)) { throw 'Plaintext key leaked to disk.' }
            } finally { $loaded.Password.Dispose() }
        } finally { $key.Dispose() }
    }

    $java = Join-Path $Bundle 'runtime\bin\java.exe'
    $jar = Join-Path $Bundle 'knitty.jar'
    $env:XDG_CONFIG_HOME = Join-Path $temporary 'config'
    $homeDirectory = Join-Path $temporary 'home'
    $library = Join-Path $temporary 'Steam library with spaces'
    $game = Join-Path $library 'steamapps\common\Core Keeper'
    $mods = Join-Path $game 'CoreKeeper_Data\StreamingAssets\Mods'
    $profileDirectory = Join-Path $temporary 'shared profile'
    [IO.Directory]::CreateDirectory((Join-Path $mods 'manual-mod')) | Out-Null
    [IO.Directory]::CreateDirectory($profileDirectory) | Out-Null
    [IO.File]::WriteAllText((Join-Path $game 'CoreKeeper.exe'), '')
    [IO.File]::WriteAllText((Join-Path $mods 'manual-mod\keep.txt'), 'keep this')
    [IO.File]::WriteAllText((Join-Path $profileDirectory 'knitty.yaml'), "schemaVersion: 1`nid: smoke`ngame: core-keeper`nmods: []`n")
    [IO.File]::WriteAllText((Join-Path $profileDirectory 'knitty.lock'), '{"schemaVersion":1,"profileId":"smoke","game":"core-keeper","packages":[]}')
    $javaArguments = @('--enable-native-access=ALL-UNNAMED', "-Duser.home=$homeDirectory", '-jar', $jar)

    $command = Join-Path $Bundle 'knitty.cmd'
    Push-Location $temporary
    try {
        & $command '--help'
        if ($LASTEXITCODE -ne 0) { throw 'Command launcher failed outside the bundle directory.' }
        & $command 'not-a-command'
        if ($LASTEXITCODE -eq 0) { throw 'Command launcher did not preserve the error exit code.' }
    } finally { Pop-Location }

    & $java @javaArguments '--help'
    if ($LASTEXITCODE -ne 0) { throw 'Bundled Java/JAR failed to start.' }
    & $java @javaArguments 'setup' '--steam-library' $library '--yes'
    if ($LASTEXITCODE -ne 0) { throw 'Windows Steam setup failed.' }
    & $java @javaArguments 'sync' 'core-keeper' '--profile' $profileDirectory '--installation' $game '--dry-run'
    if ($LASTEXITCODE -ne 0) { throw 'Windows sync preview failed.' }
    if (Test-Path -LiteralPath (Join-Path $mods '.knitty-profile.json')) { throw 'Dry-run changed the game.' }
    & $java @javaArguments 'sync' 'core-keeper' '--profile' $profileDirectory '--installation' $game '--yes'
    if ($LASTEXITCODE -ne 0) { throw 'Windows filesystem commit failed.' }
    if (!(Test-Path -LiteralPath (Join-Path $mods '.knitty-profile.json'))) { throw 'Profile membership was not saved.' }
    if ([IO.File]::ReadAllText((Join-Path $mods 'manual-mod\keep.txt')) -cne 'keep this') { throw 'Manual mod changed.' }
    Write-Host 'Windows bundle smoke tests passed.'
} finally {
    $env:XDG_CONFIG_HOME = $previousConfig
    if (Test-Path -LiteralPath $temporary) { Remove-Item -LiteralPath $temporary -Recurse -Force }
}
