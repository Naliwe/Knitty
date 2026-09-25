package knitty.cli

import com.github.ajalt.clikt.testing.test
import knitty.core.application.*
import knitty.core.model.*
import knitty.core.ports.ModProvider
import knitty.unusedSetup
import kotlin.test.*

class KnittyCommandTest {
    private val unusedProfiles: (String) -> ManageProfile = { error("Profile operation not expected") }

    @Test
    fun thunderstoreSearchUsesSharedRoutingAndErrorsDoNotRequestModioCredentials() {
        val thunderstore = ModProvider { game, query, offset ->
            assertEquals(ProviderGameId("valheim"), game)
            assertEquals("storage", query)
            assertEquals(20, offset)
            SearchOutcome.Failed(SearchFailure.Provider("thunderstore", ProviderFailure.AccessDenied))
        }
        val search =
            ProviderSearchMods(mapOf(GameId("valheim") to SearchSource(thunderstore, ProviderGameId("valheim"))))
        val result = knittyCommand(search, unusedProfiles, unusedSetup) { _, _, _ -> error("Must not open TUI") }
            .test("search valheim storage --offset 20")

        assertNotEquals(0, result.statusCode)
        assertContains(result.stderr, "thunderstore denied access")
        assertFalse(result.stderr.contains("mod.io"))
        assertFalse(result.stderr.contains("API key"))
    }

    @Test
    fun searchPassesArgumentsToApplicationPort() {
        var received: SearchRequest? = null
        val command = knittyCommand(
            {
                received = it
                SearchOutcome.Found(
                    SearchPage(
                        listOf(
                            ModSearchResult(
                                PackageId("modio", "1"), "storage",
                                "Storage", "More space", "Author", "1.0", "https://mod.io/example",
                            ),
                        ),
                        20, 22,
                    ),
                )
            },
            unusedProfiles, unusedSetup,
        ) { _, _, _ -> error("TUI should not open") }
        val result = command.test("search core-keeper storage boxes --offset 20")
        assertEquals(0, result.statusCode)
        assertEquals(SearchRequest(GameId("core-keeper"), "storage boxes", 20), received)
        assertContains(result.stdout, "Storage")
        assertContains(result.stdout, "--offset 21")
    }

    @Test
    fun errorsHaveNonzeroExitStatusAndActionableMessage() {
        val result =
            SearchCommand { SearchOutcome.Failed(SearchFailure.Provider("modio", ProviderFailure.MissingCredentials)) }
                .test("core-keeper storage")
        assertNotEquals(0, result.statusCode)
        assertContains(result.stderr, "KNITTY_MODIO_API_KEY")
    }

    @Test
    fun helpDoesNotSearchAndTuiReceivesGameContext() {
        val search = SearchMods { error("Must not search") }
        assertEquals(
            0,
            knittyCommand(search, unusedProfiles, unusedSetup) { _, _, _ -> }.test("--help").statusCode,
        )
        var game: GameId? = null
        var profileDirectory: String? = null
        var serverTarget: String? = null
        assertEquals(
            0,
            knittyCommand(search, unusedProfiles, unusedSetup) { selected, directory, target ->
                game = selected
                profileDirectory = directory
                serverTarget = target
            }.test("tui --profile /shared-beta --target ovh").statusCode,
        )
        assertEquals(GameId("core-keeper"), game)
        assertEquals("/shared-beta", profileDirectory)
        assertEquals("ovh", serverTarget)
    }

    @Test
    fun emptyResultsAreRenderedAndTerminalControlsAreRemoved() {
        assertContains(
            SearchCommand { SearchOutcome.Found(SearchPage(emptyList(), 0, 0)) }
                .test("core-keeper x").stdout,
            "No mods found",
        )
        val result = SearchCommand {
            SearchOutcome.Found(
                SearchPage(
                    listOf(
                        ModSearchResult(PackageId("modio", "1"), "x", "\u001b[31mBad", "\nsummary", "a", null, "url"),
                    ),
                    0, 1,
                ),
            )
        }.test("core-keeper x")
        assertFalse(result.stdout.contains('\u001b'))
    }
}
