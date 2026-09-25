# Project tooling

Run all commands from the repository root with the checked-in Kotlin Toolchain
launcher (`kotlin.bat` on Windows). No Python installation is required.

```sh
./kotlin test
./kotlin package -m knitty --format executable-jar

# Public JVM archive; app, launcher, README, license and third-party notices.
./kotlin run -m tooling -- package-release --version 0.7.0 --platform jvm

# Committed sources from HEAD, for the same release as the JVM artifact.
./kotlin run -m tooling -- package-source --version 0.7.0

# Native Windows image; run on Windows with a Java 21 x64 JDK.
./kotlin.bat run -m tooling -- package-release --version 0.7.0 --platform windows-x64

# Portable Windows testing bundle; can also be assembled on Linux.
./kotlin run -m tooling -- package-windows

# Optional shared profile: copies only knitty.yaml and knitty.lock.
./kotlin run -m tooling -- package-windows --profile /path/to/shared-profile

# Generate both registries' submission files from completed artifacts.
./kotlin run -m tooling -- manifests --version 0.7.0

# Both AUR recipes; --srcinfo requires makepkg on Arch Linux.
./kotlin run -m tooling -- manifests --version 0.7.0 --target aur --srcinfo

# Real Java 21 terminal and Linux native-launcher regressions, after building the executable JAR.
KNITTY_TEST_JAVA=/path/to/jdk-21/bin/java ./kotlin test -m tooling

# Opt-in SFTP test with a disposable localhost OpenSSH server.
./kotlin run -m tooling -- check-sftp
```

`package-release` and `package-windows` consume the existing executable JAR.
Build it again after changing application code. The Windows testing bundle uses
the checksum-pinned Temurin runtime in `packaging/windows/runtime.json`; public
WinGet metadata requires the native Windows image with `knitty.exe`.
Windows release assembly uses `jpackage.exe` from the JDK running the tooling.
Use `--jpackage /path/to/jdk/bin/jpackage.exe` to select another Java 21 x64 JDK.
The source JDK supplies platform metadata; the linked runtime is checked for
Java 21 without assuming it retains the full JDK's `OS_ARCH` field.
Native launchers use the executable JAR's manifest entry point, which loads its
nested application classes and dependencies. The Linux native-launcher test
exercises the same image assembly with help, setup, paths containing spaces,
and failure exit codes; CI also smoke-tests the native Windows image.

`package-source` uses `git archive HEAD`; commit release changes first. It omits
untracked files and local modifications. Publish the resulting
`knitty-VERSION-source.tar.gz` alongside the JVM archive from the same revision
and tag it `vVERSION`. Both archives are required when generating AUR manifests.
The source archive extracts into `knitty/` so Kotlin Toolchain resolves the root
module as `knitty`; the release workflow uses the same checkout directory.

The generated recipes live in `dist/manifests/aur/knitty/` (build from source) and
`dist/manifests/aur/knitty-bin/` (prebuilt JAR), each with its own `.SRCINFO` when
requested. Submit these as separate AUR packages. Both install the same command
and runtime files and conflict with one another; `knitty-bin` provides `knitty`.
The source recipe needs `jdk21-openjdk` and `curl` to build, runs the offline app
tests, and downloads the pinned Kotlin Toolchain plus Maven dependencies into
its build directory. Users of either installed package need Java 21 or newer.

The release-candidate workflow uploads artifacts and manifests for review. It
also builds and smoke-tests both AUR recipes in an Arch Linux container, then
uploads their `PKGBUILD` and generated `.SRCINFO` files as `aur-manifests`. It does
not publish GitHub releases or submit packages to AUR or WinGet. Keep local
`makepkg` builds under ignored `build/`: Arch `.BUILDINFO` records build-machine
paths and installed packages. Share allowlisted release artifacts, not the whole
working directory.

The `WinGet package` workflow validates the generated manifests and installs the
published Windows ZIP through WinGet on a disposable runner. It smoke-tests the
installed command alias and uninstalls the package, then uploads the tested
manifests as `winget-submission`. It runs for relevant packaging PRs (using 0.7.0
as the baseline release) or manually for a selected published version. The same
check can be run in an elevated disposable Windows environment with
`./scripts/test_winget.ps1 -Manifest PATH`; it installs and removes `Naliwe.Knitty`.

Before publishing, review `THIRD-PARTY-NOTICES.md` against the nested libraries in
the executable JAR and `runtime/release` in the Windows image. Keep upstream
notices and `runtime/legal/` intact. Check that the linked Lanterna and Java source
archives match the distributed versions and remain downloadable. The public
packages and Windows testing bundle all include the notices; AUR packages install
them under `/usr/share/licenses/`. Release notes live in `packaging/releases/`.

## Dependency choices

- [Commons Compress](https://commons.apache.org/proper/commons-compress/) handles
  tar metadata and ZIP attributes. Knitty keeps its archive allowlist, checksums,
  and path policy in `ReleaseArchives`.
- [Pty4J](https://github.com/JetBrains/pty4j) manages native pseudo-terminals for
  the Java 21 CLI/TUI checks. It is a test dependency only.
- Clikt parses tooling commands, and kotlinx.serialization reads/writes typed
  runtime metadata. Both already belong to the project's Kotlin ecosystem.
- The JDK HTTP client downloads runtimes with HTTPS-preserving redirects. The
  JDK's `jpackage` creates the native Windows launcher and runtime image.

The application uses [Koin Core](https://insert-koin.io/docs/reference/koin-core/context-isolation/)
only in the composition root. SnakeYAML owns YAML syntax and escaping; profile
schema validation stays in the profile adapter. Pelican and mod.io contracts use
typed kotlinx.serialization DTOs. SSHJ and Ktor continue to own SSH/SFTP and HTTP
protocol details. Domain planning, rollback decisions, credential precedence,
and deployment validation remain explicit Knitty code.
