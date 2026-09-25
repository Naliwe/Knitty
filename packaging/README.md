# Knitty

Knitty is licensed under the MIT License; see the included LICENSE file.
Bundled dependencies and Java retain their own licenses; see
THIRD-PARTY-NOTICES.md (under `app/` in the native Windows package, or
`/usr/share/licenses/knitty/` or `/usr/share/licenses/knitty-bin/` with AUR packages).
Windows runtime licenses are retained under `runtime/legal/`.

Review and synchronize Core Keeper mod profiles from the command line or an
interactive terminal. Valheim currently supports catalog browsing only.

On Windows, extract the whole ZIP and run `knitty.exe` from a terminal. Java is
bundled. With an installed AUR or WinGet package, use `knitty` from your terminal.
For a manually extracted JVM archive, Java 21+ is required; replace `knitty` below
with `java --enable-native-access=ALL-UNNAMED -jar knitty.jar`. The included Unix
launcher is for the AUR installation layout.

```text
knitty auth
knitty setup
knitty tui core-keeper
```

`auth` prompts for your own API path and key from https://mod.io/me/access, then
asks before saving them for CLI and TUI. Credentials are stored as local text at
`~/.config/knitty/modio.env`, or under an absolute `XDG_CONFIG_HOME` if configured.
Unix file permissions restrict this file to your account. On Windows, it inherits
your config directory's access permissions. Keep that directory private.

`setup` asks for the Steam library containing Core Keeper (the folder containing
`steamapps`). Close the game before applying changes. Start in a writable directory
for your profile, or choose one with `--profile PATH`; keep profiles outside the
package installation directory so upgrades do not replace them.

```text
knitty search core-keeper storage
knitty add core-keeper modio:3177992
knitty sync core-keeper --dry-run
knitty sync core-keeper
knitty outdated core-keeper
knitty update core-keeper
knitty sync core-keeper
knitty tui core-keeper --profile PATH
```

Share only `knitty.yaml` and `knitty.lock`. Each player configures their own mod.io
access and Steam library, then reviews and applies `knitty sync core-keeper`.

Existing process environment variables and profile `.env` files override saved
credentials. Run `knitty auth` again to replace a key. Delete `modio.env` to forget
the saved key; remove environment/profile overrides separately.

Source, documentation and issues: https://github.com/Naliwe/Knitty
