# AGENTS.md — Knitty

## Mission

Build **Knitty**, a Kotlin/JVM package manager for game mods.

First supported vertical: **Core Keeper + mod.io**.

Two first-class driving adapters exist from the beginning:

1. command-by-command CLI;
2. interactive TUI.

The product should make modded multiplayer reproducible while keeping package changes explicit.

## Kotlin style

Prefer idiomatic Kotlin, immutable data, small explicit types, sealed hierarchies for closed domains, coroutines, constructor injection, pure transformations, self-documenting names and narrow interfaces.

Avoid vague `Util`/`Manager`/`Helper` buckets, global mutable state, service locators, speculative abstractions, framework types in domain/application APIs, unnecessary reflection and inheritance where composition suffices.

Use comments for **why**, external constraints, or genuinely non-obvious behavior—not to narrate code.


### Kotlin and Library Conventions

- Prefer idiomatic Kotlin over Java-style code.
- Follow the default Kotlin formatting conventions. Prefer vertical whitespace and line breaks when they make code
  shorter to scan and avoid horizontal scrolling.
- Format multi-line declarations, calls, conditionals, and collection literals with one logical element per line when
  that improves readability. Keep compact expressions on one line only when they remain easy to read.
- Match the spacing, wrapping, and route-handler style used by adjacent Kotlin code; favor breathable, readable code
  over dense expressions.
- Prefer imports over fully qualified type or member names in implementation code. Keep fully qualified names only when
  they resolve a genuine naming conflict.
- Prefer the Kotlin standard library and JetBrains/Kotlinx libraries before Java or third-party alternatives.
- Prefer `kotlinx.serialization` for new JSON contracts.
- Prefer `kotlinx.datetime` in domain and application code; keep Java-time usage at integration boundaries when required
  by a dependency.
- Prefer `kotlin.io.path` over `java.io.File` for filesystem work.
- Use `@JvmInline value class` types for meaningful identifiers and constrained scalar values when it improves
  correctness.
- Use sealed interfaces or sealed classes for closed result and error hierarchies.
- Represent expected business failures explicitly; do not use exceptions or `null` to hide normal control flow.
- Use immutable values by default and keep side effects at boundaries.
- Prefer constructor injection and explicit dependencies. Do not introduce service-locator lookups or mutable global
  state.
- Do not add comments that restate the code; improve names, types, and structure instead.
- Favor clear, small types and functions over generic abstractions or reflection-heavy frameworks.

### Current Kotlin APIs and Warnings

- Keep compiler and IDE warnings clean by fixing their cause. Avoid blanket suppressions or obsolete compatibility
  code for Kotlin versions this project does not use. Preserve existing warning fixes during refactors. Compiler
  warnings are errors through `settings.kotlin.allWarningsAsErrors` in `module.yaml`.
- Use exhaustive `when` expressions for sealed types, including smart-cast narrowed types; do not add unreachable
  `else` branches. Use `data object` for singleton domain states and failures.
- Use `kotlin.time.Duration` values such as `50.milliseconds` with coroutine APIs that accept durations. Keep raw
  millisecond numbers only where an external API requires them.
- Use current standard-library APIs such as `ByteArray.toHexString()` instead of manual hex formatting, and remove
  serialization extension imports when the current library exposes the same operation as a member.
- Compare case-insensitive text with `equals(other, ignoreCase = true)` instead of allocating lowercase copies;
  still normalize values where the stored representation requires it. Give public/fixture properties explicit types
  when Java platform-type inference would leave their nullability ambiguous.
- Put the complete blocking filesystem operation inside `withContext(Dispatchers.IO)`: opening, consuming, and
  closing a lazy `Files.walk` stream all belong in that scope. Prefer one clear adapter boundary over tiny nested
  dispatcher switches around individual calls. Keep cancellation checks in long copy/hash/extraction loops.
- Propagate coroutine cancellation. Restrict `NonCancellable` to the existing commit/rollback boundaries.

### Kotlin Readability and Spacing

- Treat a function body as a sequence of short paragraphs. Separate input extraction, validation, transformation,
  side effects, and result construction with a blank line. Keep closely related declarations and their immediate
  checks together; do not insert a blank line after every statement.
- Separate sibling route handlers and other substantial DSL blocks with a blank line. Inside a handler, leave a
  blank line between loading/validating input and constructing or sending the response.
- Put an Elvis guard such as `?: return ...` on its own continuation line after a lookup or transformation. Keep the
  successful value and the early-exit path visually distinct.
- When an `if` expression or `when` branch needs multiple lines, put its result expressions on separate indented
  lines. Keep short, uniform mappings compact; separate substantial branches with a blank line.
- Name meaningful intermediate results instead of nesting several transformations, builders, or predicates inside
  one expression. Split compound validation into named conditions when that makes the decision easier to read.
  Preserve evaluation order and short-circuit behavior when extracting expressions.
- Reuse an existing local directly in `when` rather than introducing a second alias. Destructure small records when
  clear local names eliminate repeated property access and make the loop body easier to follow.
- Break substantial call chains at transformation boundaries, with one step per continuation line. When a call
  contains multi-line lambdas or nested builders, put its arguments on separate lines and make the outer call's
  boundaries clear. Do not wrap a simple inner call merely to leave a dense outer expression intact.
- Use blank lines in tests to separate setup, execution, and groups of related assertions, including distinct cases
  within a longer test. Apply the same readability standards to fixtures and helpers as to production code.
- Write one statement per line; do not join actions, assertions, or early returns with semicolons. Expand compound
  branches and constructors into readable blocks, and give nontrivial nested transformations a name.
- Use the project's Kotlin formatter as the baseline, then refine logical grouping, line breaks, and spacing for
  readability. Aim for a layout that another formatter pass leaves unchanged or very similar. If formatting
  repeatedly collapses a useful split, simplify the expression or introduce a meaningful intermediate value rather
  than fighting the formatter. Review the result to preserve the intent of nearby user edits; fitting within the
  formatter's line limit alone is not a reason to compress code.


## Architectural rules

Use hexagonal/ports-and-adapters boundaries.

### CLI and TUI

They may parse input, render output, keep frontend interaction state and translate user actions into application requests.

They must not call mod.io directly, download files, resolve dependencies, determine install paths, mutate games or contain package-management policy.

The TUI must call application ports directly. It is not a wrapper around CLI commands.

If implementing the TUI requires moving business logic out of the CLI, that logic was misplaced.

### Application

Application use cases orchestrate domain behavior and ports.

Likely use cases include `SearchMods`, `PlanInstall`, `PlanUpdate`, `ApplyChangePlan` and `SyncProfile`.

Application code must not depend on Clikt, a TUI toolkit, Ktor HTTP types, mod.io DTOs or terminal formatting.

### Domain

Use boring names such as `GameId`, `PackageId`, `PackageVersion`, `Profile`, `LockedPackage`, `ChangePlan`, `Change`, `GameInstallation`, `ModProvider`, `GameAdapter`.

Do not introduce `Thread`, `Fabric`, `Pattern`, `Shuttle`, etc. just because the product is named Knitty.

### Driven adapters

Providers and games are independent axes.

A provider obtains package metadata/artifacts from mod.io, Thunderstore, etc.

A game adapter detects/deploys to Core Keeper, Valheim, etc.

**Provider code must not know Core Keeper exists. Game-adapter code must not know mod.io exists.**

## Plan before mutation

Represent meaningful mutations as data before executing them:

```kotlin
val plan = planUpdate.plan(request)
present(plan)
applyChangePlan.apply(plan)
```

`ChangePlan` must contain semantics, not terminal formatting.

This is the common basis for CLI confirmation, TUI staged changes, `--dry-run`, lockfile diffs and future server reconciliation.

## Transactional deployment

Trend toward transactional filesystem behavior:

1. resolve and validate;
2. download to staging;
3. validate artifact/checksum when possible;
4. prepare changes;
5. commit;
6. update Knitty state after deployment outcome is known.

Do not intentionally leave half-updated installations.

Expose actionable failure types rather than generic exceptions as the application contract.

## Profiles and lockfiles

Tentative files: `knitty.yaml` and `knitty.lock`.

Lockfiles are portable. Never write machine-local paths, usernames, Steam library paths, temp directories or OS-specific deployment details into them.

Do not finalize the schema ahead of vertical-slice needs.

## CLI target

```text
knitty games
knitty search <game> <query>
knitty add <game> <package>
knitty remove <game> <package>
knitty sync <game>
knitty update <game>
knitty outdated <game>
```

Keep ANSI, tables, progress bars and terminal wording in the CLI adapter.

Do not prematurely cement game/profile/installation/target semantics.

## TUI target

Initial screen:

- game context;
- mod search input;
- selectable results;
- details pane;
- keyboard navigation.

Next:

- installed mods;
- staged add/remove/update changes;
- transaction-plan view;
- apply/cancel.

Keep toolkit-specific models in the TUI adapter.

## Preferred baseline dependencies

- Kotlin/JVM
- Kotlin Toolchain, configured through `module.yaml` and invoked with the checked-in `kotlin` / `kotlin.bat` launchers
- kotlinx.coroutines
- kotlinx.serialization
- Ktor Client
- kotlinx-datetime
- Clikt
- Mordant if useful

Evaluate the TUI ecosystem before choosing a toolkit. Lanterna is a baseline candidate, not a mandate. Prefer good Unicode, resize handling, predictable input and coroutine-friendly integration.

### Dependency Maintenance

- Declare external libraries and pinned versions in the root `libs.versions.toml`; reference them with `$libs` aliases
  in `module.yaml`. Keep related production and test artifacts on the same version.
- Use the latest stable compatible releases, verified against Maven Central or upstream release metadata. Do not
  select an alpha, beta, RC, snapshot, or floating version just because it sorts newer.
- Kotlin, its compiler plugins, and the automatic serialization/test dependencies remain managed by Kotlin Toolchain.
  Update the checked-in wrappers through Toolchain's supported workflow when necessary; do not introduce Gradle.
- Preserve documented compatibility constraints, especially Java 21 and Mordant's interactive-terminal fix. After
  upgrades, run the offline suite and the packaged Java 21 pseudo-terminal checks; piped output misses that regression.

## Implementation sequence

### Slice 1: search walking skeleton

Implement `SearchMods` end-to-end for Core Keeper/mod.io.

Both the CLI command:

```text
knitty search core-keeper storage
```

and the TUI search screen call the same application port.

Use real mod.io behavior to shape the provider boundary.

### Slice 2: plan one install

Selecting a result creates a `ChangePlan`. Render it independently in CLI and TUI.

### Slice 3: apply one install

Download/deploy one real Core Keeper mod safely and record enough local state to know what Knitty owns.

### Slice 4: profile + lock

Introduce desired/resolved state and `sync`.

### Slice 5: update

Introduce `outdated` and `update` as plan-then-apply flows.

### Slice 6: second implementation

Add either Thunderstore or Valheim, based on immediate value. Only then refactor abstractions in response to actual pressure.

**Do not begin by writing a generic dependency solver.**

## Testing

Favor tests around ports/use cases and filesystem fixtures.

At minimum cover:

- provider DTO → Knitty mapping;
- `SearchMods` with a fake provider;
- CLI invokes the application port correctly;
- TUI invokes the same port correctly;
- change-plan generation;
- deployment against temporary directories;
- lockfile serialization round-trip;
- failed staged deployment does not partially commit.

Prefer fakes for ports over mocking value objects. Separate live-network integration tests from fast tests.

## Errors

Model actionable failures such as unsupported game, provider unavailable, package not found, incompatible version, download failure, checksum mismatch, installation not found, deployment conflict and filesystem commit failure.

Adapters decide how these are displayed. Domain/application code does not print.

## Scope discipline

Before adding an abstraction, ask:

> Does the current vertical slice require this, or am I designing hypothetical Nexus/Valheim/Windows behavior?

If hypothetical, defer it.

Before adding a dependency, ask whether it earns its weight.

Before putting logic in a frontend, ask whether the other frontend needs the same decision.

## Definition of done for a slice

A slice is done when:

- both relevant driving adapters exercise the same application behavior;
- architectural boundaries remain intact;
- behavior has focused tests;
- errors are explicit;
- no unnecessary future-system abstraction was introduced;
- the path works against the real provider/game where applicable.
