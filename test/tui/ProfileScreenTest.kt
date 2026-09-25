package knitty.tui

import com.googlecode.lanterna.TerminalSize
import com.googlecode.lanterna.input.KeyStroke
import com.googlecode.lanterna.input.KeyType
import com.googlecode.lanterna.screen.TerminalScreen
import com.googlecode.lanterna.terminal.virtual.DefaultVirtualTerminal
import knitty.*
import knitty.core.application.SearchMods
import knitty.core.model.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.*
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalCoroutinesApi::class)
class ProfileScreenTest {
    @Test
    fun progressShowsMeasuredTransfersAndAnimatesUnknownTotalsWithoutClearing() {
        var clears = 0
        val terminal = object : DefaultVirtualTerminal(TerminalSize(40, 12)) {
            override fun clearScreen() {
                clears++
                super.clearScreen()
            }
        }
        TerminalScreen(terminal).use { screen ->
            screen.startScreen()
            val transfer = OperationProgress(ProgressStage.Uploading, "Example", 50, 100)
            renderProfileScreen(screen, ProfileScreenState.Applying, 0, transfer)
            val initialClears = clears
            fun text(): String = (0 until 12).joinToString("\n") { y ->
                (0 until 40).joinToString("") { x -> screen.getFrontCharacter(x, y).characterString }
            }

            fun bar() = (2 until 38).map { screen.getFrontCharacter(it, 6).backgroundColor }

            assertContains(text(), "Uploading mods")
            assertContains(text(), "50%")
            assertEquals(18, bar().count { it == TuiPalette.accent })
            assertFalse(text().contains("quit"))

            val waiting = OperationProgress(ProgressStage.StoppingServer)
            renderProfileScreen(screen, ProfileScreenState.Applying, 0, waiting, tick = 0)
            val first = bar()
            renderProfileScreen(screen, ProfileScreenState.Applying, 0, waiting, tick = 5)
            assertNotEquals(first, bar())
            assertContains(text(), "Stopping server")
            assertFalse(text().contains("%"))
            assertEquals(initialClears, clears)
        }
    }

    @Test
    fun remoteReviewShowsTargetDowntimeAndGameAutoUpdate() {
        TerminalScreen(DefaultVirtualTerminal(TerminalSize(120, 30))).use { screen ->
            screen.startScreen()
            renderProfileScreen(screen, ProfileScreenState.Sync(sampleServerSync), 0)
            val text = (0 until 30).joinToString("\n") { y ->
                (0 until 120).joinToString("") { x -> screen.getFrontCharacter(x, y).characterString }
            }

            assertContains(text, "Server: ovh")
            assertContains(text, "panel.example.com")
            assertContains(text, "stop the server")
            assertContains(text, "auto-update")
            assertContains(text, "y apply sync")
            assertFalse(text.contains("Close Core Keeper"))
        }
    }

    @Test
    fun dependencyReviewShowsTheFullGraphAndWhichModsWereRequested() {
        TerminalScreen(DefaultVirtualTerminal(TerminalSize(100, 24))).use { screen ->
            screen.startScreen()
            renderProfileScreen(screen, ProfileScreenState.Edit(sampleDependencyEdit), 0)
            val text = (0 until 24).joinToString("\n") { y ->
                (0 until 100).joinToString("") { x -> screen.getFrontCharacter(x, y).characterString }
            }
            assertContains(text, "+ Shared Library · dependency")
            assertContains(text, "+ Example · requested")
            assertContains(text, "Requires: modio:2")
            assertContains(text, "2 add")
            assertContains(text, "y save profile")
        }
    }

    @Test
    fun planScrollClampsAndOnlyActionableStatesOfferConfirmation() {
        TerminalScreen(DefaultVirtualTerminal(TerminalSize(40, 12))).use { screen ->
            screen.startScreen()
            val plan = sampleSync.copy(untouched = (1..40).map { "Preserved mod $it" })
            val end = renderProfileScreen(screen, ProfileScreenState.Sync(plan), Int.MAX_VALUE)
            assertTrue(end > 0)
            assertEquals(end - 1, renderProfileScreen(screen, ProfileScreenState.Sync(plan), end - 1))

            fun footer(): String =
                (screen.terminalSize.rows - 2 until screen.terminalSize.rows).joinToString(" ") { y ->
                    (0 until screen.terminalSize.columns).joinToString("") { x ->
                        screen.getFrontCharacter(
                            x,
                            y,
                        ).characterString
                    }
                }

            assertContains(footer(), "y apply sync")
            renderProfileScreen(screen, ProfileScreenState.Done(synchronized = false), 0)
            assertContains(footer(), "s review sync")
            assertFalse(footer().contains("y confirm"))
            renderProfileScreen(screen, ProfileScreenState.Failed(ProfileFailure.MissingProfile), 0)
            assertFalse(footer().contains("y "))
            renderProfileScreen(screen, ProfileScreenState.Applying, 0)
            assertFalse(footer().contains("quit"))
            assertFalse(footer().contains("confirm"))
        }
    }

    @Test
    fun keyboardOutdatedAndUpdateWorkWithoutSearchResultsAndRenderExactDiff() = runTest {
        var saves = 0
        val service = object : ProfileFake() {
            override suspend fun planUpdate(game: GameId): ProfileResult<ProfileEditPlan> {
                assertEquals(profileGame, game)
                return ProfileResult.Success(sampleUpdate)
            }

            override suspend fun save(plan: ProfileEditPlan): ProfileResult<Unit> {
                assertEquals(sampleUpdate, plan)
                saves++
                return ProfileResult.Success(Unit)
            }
        }
        val terminal = DefaultVirtualTerminal(TerminalSize(100, 24))
        TerminalScreen(terminal).use { screen ->
            screen.startScreen()
            val job = backgroundScope.launch {
                runSearchScreen(screen, profileGame, { error("No search needed") }, service)
            }

            fun key(key: KeyStroke) {
                terminal.addInput(key)
                advanceTimeBy(80.milliseconds)
                runCurrent()
            }

            fun text(): String = (0 until screen.terminalSize.rows).joinToString("\n") { y ->
                (0 until screen.terminalSize.columns).joinToString("") { x ->
                    screen.getFrontCharacter(x, y).characterString
                }
            }

            key(KeyStroke(KeyType.Tab))
            key(KeyStroke('o', false, false))
            assertContains(text(), "Published release changes")
            assertContains(text(), "1.0 (file 10) -> 2.0 (file 11)")
            key(KeyStroke('y', false, false))
            assertEquals(0, saves)

            key(KeyStroke('u', false, false))
            assertContains(text(), "Profile plan")
            assertContains(text(), "1.0 (file 10) -> 2.0 (file 11)")
            key(KeyStroke(KeyType.Escape))
            assertEquals(0, saves)

            key(KeyStroke('u', false, false))
            key(KeyStroke('y', false, false))
            assertEquals(1, saves)
            assertContains(text(), "Press s to review and apply sync")
            key(KeyStroke('q', false, false))
            assertTrue(job.isCompleted)
        }
    }

    @Test
    fun keyboardAddSaveAndSyncUseProfilePortsWithoutLegacyInstaller() = runTest {
        var saved = false
        var applied = false
        var addRequests = 0
        val sync = sampleSync.copy(untouched = sampleSync.untouched + (1..40).map { "Preserved mod $it" })
        val service = object : ProfileFake() {
            override suspend fun add(game: GameId, reference: PackageReference): ProfileResult<ProfileEditPlan> {
                addRequests++
                return ProfileResult.Success(sampleEdit)
            }

            override suspend fun save(plan: ProfileEditPlan): ProfileResult<Unit> {
                assertEquals(sampleEdit, plan)
                saved = true
                return ProfileResult.Success(Unit)
            }

            override suspend fun planSync(game: GameId, directory: String?, target: String?, progress: ProgressSink) =
                ProfileResult.Success(sync)

            override suspend fun apply(plan: SyncPlan, progress: ProgressSink): ProfileResult<Unit> {
                assertEquals(sync, plan)
                applied = true
                return ProfileResult.Success(Unit)
            }
        }
        val terminal = DefaultVirtualTerminal(TerminalSize(100, 24))
        TerminalScreen(terminal).use { screen ->
            screen.startScreen()
            val search = SearchMods {
                SearchOutcome.Found(
                    SearchPage(
                        listOf(
                            ModSearchResult(
                                profilePackage.id,
                                "example",
                                "Example",
                                "Description",
                                "Author",
                                "1.0",
                                "url",
                            ),
                        ),
                        0, 1,
                    ),
                )
            }
            val job = backgroundScope.launch {
                runSearchScreen(screen, profileGame, search, service)
            }

            fun key(key: KeyStroke) {
                terminal.addInput(key)
                advanceTimeBy(80.milliseconds)
                runCurrent()
            }

            fun text(): String = (0 until screen.terminalSize.rows).joinToString("\n") { y ->
                (0 until screen.terminalSize.columns).joinToString("") { x ->
                    screen.getFrontCharacter(
                        x,
                        y,
                    ).characterString
                }
            }
            terminal.addInput(KeyStroke('e', false, false))
            key(KeyStroke(KeyType.Enter))
            key(KeyStroke(KeyType.Enter))
            assertContains(text(), "MOD DETAILS")
            assertEquals(0, addRequests)

            key(KeyStroke('a', false, false))
            assertEquals(1, addRequests)
            assertContains(text(), "Profile plan")
            assertFalse(saved)
            key(KeyStroke('y', false, false))
            assertTrue(saved)
            assertFalse(applied)
            key(KeyStroke('s', false, false))
            assertContains(text(), "Sync plan")
            assertContains(text(), "Outside profile, preserved: manual")
            val firstPage = text()
            key(KeyStroke(KeyType.PageDown))
            assertNotEquals(firstPage, text())
            key(KeyStroke(KeyType.PageUp))
            assertEquals(firstPage, text())

            key(KeyStroke('y', false, false))
            assertTrue(applied)
            assertContains(text(), "Profile synchronized")
            key(KeyStroke('q', false, false))
            assertTrue(job.isCompleted)
        }
    }
}
