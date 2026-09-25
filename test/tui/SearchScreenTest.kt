package knitty.tui

import com.googlecode.lanterna.TerminalSize
import com.googlecode.lanterna.input.KeyStroke
import com.googlecode.lanterna.input.KeyType
import com.googlecode.lanterna.screen.TerminalScreen
import com.googlecode.lanterna.terminal.virtual.DefaultVirtualTerminal
import knitty.ProfileFake
import knitty.core.application.ProviderSearchMods
import knitty.core.application.SearchMods
import knitty.core.application.SearchRequest
import knitty.core.application.SearchSource
import knitty.core.model.*
import knitty.core.ports.ModProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.*
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalCoroutinesApi::class)
class SearchScreenTest {
    @Test
    fun incrementalRenderingErasesOldTextWithoutClearingTheTerminal() {
        var clears = 0
        val terminal = object : DefaultVirtualTerminal(TerminalSize(100, 24)) {
            override fun clearScreen() {
                clears++
                super.clearScreen()
            }
        }
        val mods = listOf(
            ModSearchResult(
                PackageId("modio", "1"),
                "one",
                "Long first title",
                "First description",
                "Alice",
                "1",
                "url",
            ),
            ModSearchResult(PackageId("modio", "2"), "two", "Short", "Second", "Bob", "2", "url"),
        )
        val state = SearchState(page = SearchPage(mods, 0, mods.size))
        val game = GameId("core-keeper")
        TerminalScreen(terminal).use { screen ->
            screen.startScreen()
            renderSearchScreen(screen, game, state, "boxes 箱", editing = true)
            val initialClears = clears

            renderSearchScreen(screen, game, state, "box", editing = true)
            assertFalse(screenText(screen).contains("boxes"))
            assertFalse(screenText(screen).contains("箱"))
            assertNotNull(screen.cursorPosition)

            renderSearchScreen(screen, game, state.copy(selection = 1), "box", editing = false)
            assertContains(screenText(screen), "Second")
            assertFalse(screenText(screen).contains("First description"))
            assertNull(screen.cursorPosition)
            assertEquals(initialClears, clears, "Typing and selection must use incremental terminal updates")
        }
    }

    @Test
    fun catalogOnlyScreenSearchesSecondProviderAndDisablesProfileActions() = runTest {
        val terminal = DefaultVirtualTerminal(TerminalSize(100, 24))
        TerminalScreen(terminal).use { screen ->
            screen.startScreen()
            var calls = 0
            val provider = ModProvider { game, query, offset ->
                assertEquals(ProviderGameId("valheim"), game)
                assertEquals("storage", query)
                assertEquals(0, offset)
                calls++
                SearchOutcome.Found(
                    SearchPage(
                        listOf(
                            ModSearchResult(
                                PackageId("thunderstore", "Author-Storage"),
                                "Storage", "Storage", "More space", "Author", "1.0.0",
                                "https://thunderstore.io/c/valheim/p/Author/Storage/",
                            ),
                        ),
                        0, 1,
                    ),
                )
            }
            val search =
                ProviderSearchMods(mapOf(GameId("valheim") to SearchSource(provider, ProviderGameId("valheim"))))
            val profiles = object : ProfileFake() {
                override fun supports(game: GameId): Boolean {
                    assertEquals(GameId("valheim"), game)
                    return false
                }
            }
            val job = backgroundScope.launch { runSearchScreen(screen, GameId("valheim"), search, profiles) }
            "storage".forEach { terminal.addInput(KeyStroke(it, false, false)) }
            terminal.addInput(KeyStroke(KeyType.Enter))
            advanceTimeBy(80.milliseconds)
            runCurrent()

            assertEquals(1, calls)
            assertContains(screenText(screen), "catalog only; installation unavailable")
            assertContains(screenText(screen), "thunderstore:Author-Storage")
            assertFalse(screenText(screen).contains("a/Enter: add"))

            "arsluoy".forEach { terminal.addInput(KeyStroke(it, false, false)) }
            advanceTimeBy(80.milliseconds)
            runCurrent()
            assertContains(screenText(screen), "catalog only; installation unavailable")
            assertEquals(1, calls)

            terminal.addInput(KeyStroke('q', false, false))
            advanceTimeBy(80.milliseconds)
            runCurrent()
            assertTrue(job.isCompleted)
        }
    }

    @Test
    fun keyboardSearchSelectionResizeAndQuitWorkThroughScreen() = runTest {
        val terminal = DefaultVirtualTerminal(TerminalSize(100, 24))
        TerminalScreen(terminal).use { screen ->
            screen.startScreen()
            var request: SearchRequest? = null
            val search = SearchMods {
                request = it
                SearchOutcome.Found(
                    SearchPage(
                        listOf(
                            ModSearchResult(
                                PackageId("modio", "1"),
                                "one",
                                "Storage 箱",
                                "First details",
                                "Alice",
                                "1.0",
                                "url",
                            ),
                            ModSearchResult(
                                PackageId("modio", "2"),
                                "two",
                                "Boxes",
                                "Second details",
                                "Bob",
                                null,
                                "url",
                            ),
                        ),
                        0, 2,
                    ),
                )
            }
            val job = backgroundScope.launch {
                runSearchScreen(
                    screen,
                    GameId("core-keeper"),
                    search,
                    object : ProfileFake() {},
                )
            }
            "storage".forEach { terminal.addInput(KeyStroke(it, false, false)) }
            terminal.addInput(KeyStroke(KeyType.Enter))
            runCurrent()
            advanceTimeBy(80.milliseconds)
            runCurrent()
            assertEquals(SearchRequest(GameId("core-keeper"), "storage"), request)
            assertContains(screenText(screen), "First details")
            assertContains(screenText(screen), "Storage")

            terminal.addInput(KeyStroke(KeyType.ArrowDown))
            advanceTimeBy(80.milliseconds)
            runCurrent()
            assertContains(screenText(screen), "Second details")

            terminal.terminalSize = TerminalSize(50, 20)
            advanceTimeBy(80.milliseconds)
            runCurrent()
            assertContains(screenText(screen), "Second details")
            terminal.terminalSize = TerminalSize(20, 6)
            advanceTimeBy(80.milliseconds)
            runCurrent()
            assertContains(screenText(screen), "Resize terminal")
            terminal.addInput(KeyStroke('q', false, false))
            advanceTimeBy(80.milliseconds)
            runCurrent()
            assertTrue(job.isCompleted)
        }
    }

    @Test
    fun focusHelpAndScrollableDetailsWorkAtMinimumSize() = runTest {
        val terminal = DefaultVirtualTerminal(TerminalSize(40, 12))
        TerminalScreen(terminal).use { screen ->
            screen.startScreen()
            var searches = 0
            val search = SearchMods {
                searches++
                SearchOutcome.Found(
                    SearchPage(
                        listOf(
                            ModSearchResult(
                                PackageId("modio", "1"), "storage", "Storage 箱",
                                "A long description with lots of useful information. ".repeat(20),
                                "Author", "1.0", "https://example.com/full-details",
                            ),
                        ),
                        0, 1,
                    ),
                )
            }
            val job = backgroundScope.launch {
                runSearchScreen(screen, GameId("core-keeper"), search, object : ProfileFake() {})
            }

            fun key(key: KeyStroke) {
                terminal.addInput(key)
                advanceTimeBy(80.milliseconds)
                runCurrent()
            }

            runCurrent()
            assertNotNull(screen.cursorPosition)
            "箱".repeat(30).forEach { terminal.addInput(KeyStroke(it, false, false)) }
            advanceTimeBy(80.milliseconds)
            runCurrent()
            assertTrue(screen.cursorPosition.column in 0 until 40)
            assertContains(screenText(screen), "Enter search")

            key(KeyStroke(KeyType.Enter))
            assertNull(screen.cursorPosition)
            assertEquals(1, searches)
            assertContains(screenText(screen), "? keys")
            assertContains(screenText(screen), "q quit")

            key(KeyStroke('?', false, false))
            assertContains(screenText(screen), "QUICK REFERENCE")
            assertContains(screenText(screen), "resolve lock")
            key(KeyStroke(KeyType.Escape))
            assertTrue(job.isActive)

            key(KeyStroke('d', false, false))
            assertContains(screenText(screen), "MOD DETAILS")
            repeat(15) { key(KeyStroke(KeyType.PageDown)) }
            assertContains(screenText(screen), "https://example.com/full-details")
            key(KeyStroke(KeyType.ArrowUp))
            assertContains(screenText(screen), "MOD DETAILS")
            key(KeyStroke(KeyType.Escape))
            assertContains(screenText(screen), "MOD CATALOG")
            assertEquals(1, searches)

            key(KeyStroke('/', false, false))
            assertNotNull(screen.cursorPosition)
            key(KeyStroke(KeyType.ArrowDown))
            assertNull(screen.cursorPosition)
            assertContains(screenText(screen), "Storage")

            key(KeyStroke(KeyType.Escape))
            assertTrue(job.isActive)
            assertNotNull(screen.cursorPosition)
            key(KeyStroke(KeyType.Escape))
            assertTrue(job.isActive)
            assertNull(screen.cursorPosition)
            key(KeyStroke('q', false, false))
            assertTrue(job.isCompleted)
        }
    }

    @Test
    fun resultsStayVisibleAndUseDistinctSelectionAcrossWidths() {
        val mods = (1..30).map { number ->
            ModSearchResult(
                PackageId("modio", "$number"),
                "mod-$number",
                "Mod $number",
                "Details",
                "Author",
                "1",
                "url",
            )
        }
        for (size in listOf(TerminalSize(40, 12), TerminalSize(50, 20), TerminalSize(100, 24))) {
            TerminalScreen(DefaultVirtualTerminal(size)).use { screen ->
                screen.startScreen()
                val state = SearchState(page = SearchPage(mods, 0, 30), selection = 29)
                renderSearchScreen(screen, GameId("core-keeper"), state, "mods", editing = false)

                assertContains(screenText(screen), "› Mod 30")
                assertContains(screenText(screen), "q quit")
                assertEquals(TuiPalette.background, screen.getFrontCharacter(0, 2).backgroundColor)
                val selectedCells = (0 until size.rows).flatMap { y ->
                    (0 until size.columns).map { x -> screen.getFrontCharacter(x, y) }
                }.filter { it.backgroundColor == TuiPalette.accent }
                assertTrue(selectedCells.isNotEmpty())
                assertTrue(selectedCells.all { it.foregroundColor == TuiPalette.background || it.characterString == " " })
            }
        }
    }

    private fun screenText(screen: TerminalScreen): String =
        (0 until screen.terminalSize.rows).joinToString("\n") { row ->
            (0 until screen.terminalSize.columns).joinToString("") { column ->
                screen.getFrontCharacter(column, row).characterString
            }
        }
}
