param([switch]$Configure)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
. (Join-Path $PSScriptRoot 'credentials.ps1')

$java = Join-Path $PSScriptRoot 'runtime\bin\java.exe'
$jar = Join-Path $PSScriptRoot 'knitty.jar'
$profileDirectory = Join-Path $PSScriptRoot 'profile'
$credentialsFile = Join-Path ([Environment]::GetFolderPath('LocalApplicationData')) 'Knitty\modio.json'
$exitCode = 0

try {
    Write-Host 'Knitty - Core Keeper shared mods'
    Write-Host ''
    if (!(Test-Path -LiteralPath $java -PathType Leaf) -or !(Test-Path -LiteralPath $jar -PathType Leaf)) {
        throw 'Extract the whole ZIP first (Extract All), then open Start Knitty.cmd in the extracted folder.'
    }
    if (!$Configure -and (!(Test-Path -LiteralPath (Join-Path $profileDirectory 'knitty.yaml') -PathType Leaf) -or
        !(Test-Path -LiteralPath (Join-Path $profileDirectory 'knitty.lock') -PathType Leaf))) {
        throw 'Your shared profile is missing. Copy knitty.yaml and knitty.lock into the profile folder, then try again.'
    }

    if ($Configure -or !(Test-Path -LiteralPath $credentialsFile)) {
        Write-Host 'One-time setup: open https://mod.io/me/access in your browser.'
        Write-Host 'Sign in with your own account, then copy your API path and API key.'
        Write-Host 'The user ID is already in the API path. Do not use your friend''s key.'
        $apiPath = (Read-Host 'API path (https://u-...modapi.io/v1)').Trim()
        if ($apiPath -cnotmatch '^https://[ug]-[1-9][0-9]*\.(modapi\.io|test\.mod\.io)/v1/?$') {
            throw 'Copy the complete API path from mod.io, including https:// and /v1.'
        }
        $apiKey = Read-Host 'API key (hidden while typing or pasting)' -AsSecureString
        try {
            if ($apiKey.Length -eq 0) { throw 'No API key entered. Run Start Knitty.cmd again.' }
            Write-Host ''
            Write-Host 'Choose the Steam library containing Core Keeper: the folder with steamapps inside it.'
            Add-Type -AssemblyName System.Windows.Forms
            $picker = New-Object System.Windows.Forms.FolderBrowserDialog
            try {
                $picker.Description = 'Select your Steam library folder (contains steamapps).'
                $picker.ShowNewFolderButton = $false
                if ($picker.ShowDialog() -ne [System.Windows.Forms.DialogResult]::OK) {
                    throw 'Setup cancelled. Run Start Knitty.cmd when you are ready.'
                }
                & $java '--enable-native-access=ALL-UNNAMED' '-jar' $jar 'setup' '--steam-library' $picker.SelectedPath '--yes'
                if ($LASTEXITCODE -ne 0) { throw 'Steam setup failed. Run Setup Knitty.cmd and choose the library folder again.' }
            } finally {
                $picker.Dispose()
            }
            Save-KnittyCredentials -File $credentialsFile -ApiPath $apiPath -ApiKey $apiKey
        } finally {
            $apiKey.Dispose()
        }
        Write-Host 'Setup saved for your Windows account.'
    }

    if (!$Configure) {
        $credentials = Read-KnittyCredentials -File $credentialsFile
        try {
            $env:KNITTY_MODIO_API_PATH = $credentials.UserName
            $env:KNITTY_MODIO_API_KEY = $credentials.GetNetworkCredential().Password
            Write-Host ''
            Write-Host 'Close Core Keeper first. Review the changes below; type y only to apply them.'
            & $java '--enable-native-access=ALL-UNNAMED' '-jar' $jar 'sync' 'core-keeper' '--profile' $profileDirectory
            $exitCode = $LASTEXITCODE
            if ($exitCode -ne 0) {
                Write-Host 'Knitty could not finish. Keep this window open and share the error above with your host.'
                Write-Host 'For a changed API key or Steam location, run Setup Knitty.cmd.'
            }
        } finally {
            Remove-Item Env:KNITTY_MODIO_API_KEY -ErrorAction SilentlyContinue
            Remove-Item Env:KNITTY_MODIO_API_PATH -ErrorAction SilentlyContinue
            $credentials.Password.Dispose()
        }
    }
} catch {
    # Do not print exception objects: malformed credential files could contain secrets.
    Write-Host 'Knitty could not finish:' -ForegroundColor Red
    $message = $_.Exception.Message
    if ($_.Exception -is [Management.Automation.RuntimeException] -and $_.CategoryInfo.Category -eq 'OperationStopped') {
        Write-Host $message
    } else {
        Write-Host 'Check that the ZIP is fully extracted into a writable folder and run Setup Knitty.cmd again.'
    }
    $exitCode = 1
}
exit $exitCode
