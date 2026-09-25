# Windows DPAPI binds the saved key to the current Windows user on this PC.
function Save-KnittyCredentials {
    param([string]$File, [string]$ApiPath, [Security.SecureString]$ApiKey)
    $parent = Split-Path -Parent $File
    [IO.Directory]::CreateDirectory($parent) | Out-Null
    $temporary = Join-Path $parent ([Guid]::NewGuid().ToString() + '.tmp')
    try {
        $settings = @{
            schemaVersion = 1
            apiPath = $ApiPath
            encryptedKey = ConvertFrom-SecureString $ApiKey
        }
        [IO.File]::WriteAllText($temporary, ($settings | ConvertTo-Json))
        if ([IO.File]::Exists($File)) {
            # PowerShell converts $null to an empty string here; .NET needs a real null backup path.
            [IO.File]::Replace($temporary, $File, [System.Management.Automation.Language.NullString]::Value)
        } else {
            [IO.File]::Move($temporary, $File)
        }
    } finally {
        if ([IO.File]::Exists($temporary)) { [IO.File]::Delete($temporary) }
    }
}

function Read-KnittyCredentials {
    param([string]$File)
    try {
        $settings = Get-Content -LiteralPath $File -Raw | ConvertFrom-Json
        if ($settings.schemaVersion -ne 1 -or
            $settings.apiPath -cnotmatch '^https://[ug]-[1-9][0-9]*\.(modapi\.io|test\.mod\.io)/v1/?$') {
            throw 'Invalid credential settings.'
        }
        $key = ConvertTo-SecureString $settings.encryptedKey
        if ($key.Length -eq 0) { throw 'Empty API key.' }
        return [Management.Automation.PSCredential]::new($settings.apiPath, $key)
    } catch {
        throw 'Could not read your saved mod.io login. Run Setup Knitty.cmd to replace it on this PC.'
    }
}
