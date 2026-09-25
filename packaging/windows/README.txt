KNITTY - WINDOWS x64 BETA

For the latest CLI/TUI and auth test session, follow TESTING.txt.
The instructions below are for the existing shared-profile launcher.

1. Right-click the ZIP and choose Extract All. Open the extracted Knitty folder.
2. If your host sent knitty.yaml and knitty.lock separately, put both in profile.
3. Close Core Keeper, then double-click Start Knitty.cmd.
4. First time only: enter your own mod.io API path and API key from
   https://mod.io/me/access, then choose your Steam library folder.
   Choose the folder containing steamapps, for example:
   C:\Program Files (x86)\Steam   or   D:\SteamLibrary
5. Read the proposed mod changes and type y to apply them.
   Wait for "Profile synchronized." before starting Core Keeper from Steam.

Java is included. No Java install, developer tools, or administrator account
is needed for a normal writable Steam library. Internet is needed to download mods.

Next time: close the game and double-click Start Knitty.cmd again.
When your host sends a new profile, replace BOTH files in profile first.
Keep the same profile identity; changing to an unrelated profile is not automated.
To change your API key/path or Steam folder, double-click Setup Knitty.cmd.

Your key is encrypted for your Windows account and saved outside this folder at
%LOCALAPPDATA%\Knitty\modio.json. Steam settings are saved under your user profile
in .config\knitty\settings.json. Do not send either of these local settings files.

This is an unsigned beta. Only run the copy you received from your trusted host.
If Windows blocks it, ask your host for help; do not turn off antivirus protection.
The launcher uses Windows PowerShell for this process only; it does not change
your machine's script policy. Managed work PCs may block scripts.

If something fails, leave the window open and send your host the error text.
Required dependencies are included in the shared lockfile and reviewed during sync.
Sync installs the shared pins; it does not automatically upgrade to newer releases.
CoreLib installation, in-game loading on Windows, and multiplayer between Linux
and Windows have been confirmed by beta testers.

The bundled Eclipse Temurin Java runtime includes its licenses in runtime\legal.
Runtime source/version/checksum and the app checksum are in build-info.json.
Knitty is licensed under MIT; see LICENSE.
