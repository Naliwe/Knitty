# Knitty

Licensed under the [MIT License](LICENSE).
Bundled libraries retain their own licenses; see [third-party notices](THIRD-PARTY-NOTICES.md).

## Quick start

**Java 21+**

Clone or extract the sources into a directory named `knitty`, which Kotlin
Toolchain uses as the root module name. Run commands from that directory.

```sh
./kotlin package -m knitty --format executable-jar
```

**One-time setup**

```sh
./knitty auth
./knitty setup
./knitty tui core-keeper
```

`auth` saves your own [mod.io API Access](https://mod.io/me/access) values outside
shared profiles. Existing `KNITTY_MODIO_API_KEY` and `KNITTY_MODIO_API_PATH`
environment variables and profile `.env` files still work.

AUR and WinGet packaging is being prepared; no published package is claimed yet.
The AUR recipes offer `knitty` (build from source) and `knitty-bin` (prebuilt JAR).
Installed packages use `knitty` instead of `./knitty`.

## Examples

```sh
# Search
./knitty search core-keeper storage

# Add and deploy
./knitty add core-keeper modio:3177992
./knitty sync core-keeper --dry-run
./knitty sync core-keeper

# Update
./knitty outdated core-keeper
./knitty update core-keeper
./knitty sync core-keeper

# Remove
./knitty remove core-keeper modio:3177992
./knitty sync core-keeper

# Separate profile
./knitty tui core-keeper --profile ~/mods/core-keeper

# Configured Pelican server target
./knitty sync core-keeper --target ovh --dry-run
./knitty sync core-keeper --target ovh
./knitty tui core-keeper --target ovh

# Valheim catalog browsing
./knitty search valheim storage
./knitty tui valheim
```

## Contributing

- [Contributor instructions](AGENTS.md)
- [Roadmap](ROADMAP.md)

```sh
./kotlin test
./kotlin package -m knitty --format executable-jar
KNITTY_TEST_JAVA=/path/to/jdk-21/bin/java ./kotlin test -m tooling
```

The `tooling` module contains release assembly and packaging checks. See its
[commands and dependency choices](tooling/README.md). It is separate from the
shipped application. Windows contributors use `kotlin.bat` for the same commands.

Application wiring lives in [ApplicationServices.kt](src/ApplicationServices.kt).
Koin owns an isolated container and a scope per profile; application ports and
adapters use constructor injection without depending on Koin.
