# Knitty — Initial Roadmap

## v0.1: Search skeleton

- [x] Kotlin Toolchain JVM application boots
- [x] CLI root command
- [x] TUI entry point
- [x] `GameId` and provider identity mapping
- [x] mod.io HTTP adapter
- [x] `SearchMods` application port/use case
- [x] `knitty search core-keeper <query>`
- [x] TUI search/results/details
- [x] fake-provider application tests
- [x] provider mapping tests
- [x] live mod.io integration test separated from unit suite
- [x] Verify live Core Keeper search with a valid mod.io API key (confirmed by user)

## v0.2: Plan + install one mod

- [x] package/version/artifact domain types
- [x] select an exact published file (deployment compatibility checks belong to apply)
- [x] `ChangePlan`
- [x] CLI plan renderer (`add --dry-run`)
- [x] TUI staged-plan view
- [x] Verify a live install plan with a dependency-free Core Keeper mod (CoreLib 5.0.0)
- [x] download staging
- [x] Core Keeper installation detection
- [x] One-time Steam library setup, shared by CLI and TUI
- [x] Core Keeper deployment adapter
- [x] local Knitty ownership/state
- [x] recoverable/transactional commit behavior
- [x] CLI and TUI confirmation/apply through the same application port
- [x] Verify a real mod download/deployment and launch Core Keeper with it (CoreLib; user confirmed one local mod loaded)

## v0.3: Reproducible state

- [x] `knitty.yaml`
- [x] `knitty.lock`
- [x] portable lockfile model
- [x] `add`
- [x] `remove`
- [x] `sync`
- [x] serialization round-trip tests
- [x] reconcile installed vs desired vs locked state
- [x] Windows portable ZIP with bundled Java and first-launch setup

- [x] Verify the launcher and real deployment on Windows (tester confirmed CoreLib installed after the Mordant fix)
- [x] Verify CoreLib loads in Core Keeper on Windows (confirmed by user)
- [x] Verify profile sharing and multiplayer with beta testers (Linux/Windows CoreLib flow confirmed by user)

## v0.4: Updates

- [x] `outdated`
- [x] `update`
- [x] update planning
- [x] confirmation/staging in both CLI and TUI
- [x] safe apply through transactional sync, including replacement rollback tests
- [x] useful diff output
- [x] Verify update planning against live mod.io (CoreLib metadata and synthetic previous pin; test pass confirmed by user)
- [ ] Verify a real mod version replacement and launch Core Keeper with the updated profile (deferred until a release is
  available; does not block v0.5)

## v0.5: Pressure test

First step: Thunderstore catalog browsing for Valheim.

- [x] Thunderstore search provider and package mapping
- [x] Per-game provider routing through the shared application port
- [x] CLI and TUI catalog browsing with explicit installation capability
- [x] Stream large catalogs and cache search records for paging
- [x] Verify live Thunderstore search without credentials

Follow-up: choose the next deployment vertical before adding Thunderstore artifact
resolution/downloads, loader/dependency support, and a Valheim game adapter. Catalog
browsing does not imply Valheim installation support.

Keep using real implementations to discover which abstractions are actually generic.

## v0.6: Core Keeper server sync

- [x] Named Pelican server targets in profiles, with credentials outside portable files
- [x] Explicit CLI/TUI target selection through shared sync planning
- [x] SFTP staging, ownership checks, remote verification, and recoverable publication
- [x] Pelican graceful stop, offline verification, and restart after successful deployment
- [x] Failure-injection tests and real localhost SFTP protocol check
- [ ] Verify the installed Pelican/Wings instance and launch Core Keeper with a synced profile

## First public packaging

- [x] Focused composition/profile orchestration cleanup and formatting pass
- [x] Personal mod.io setup shared by CLI and TUI, outside portable profiles
- [x] Versioned JVM and native Windows packaging scripts with checksums
- [x] AUR and WinGet manifest generation from final artifact hashes
- [x] Manual release-candidate CI workflow, without automatic publication
- [x] Adopt MIT and include LICENSE in release artifacts
- [ ] Validate native Windows package and WinGet install/upgrade/uninstall
- [ ] Publish release assets and submit AUR/WinGet packages

## Later

- dependency/conflict solver
- server/client semantics
- config synchronization
- profiles and multiple installations
- Steam/Proton discovery
- GitHub Releases
- Nexus
- rollback/history
- provider aggregation
