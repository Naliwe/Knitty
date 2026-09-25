package knitty.core

import knitty.core.application.ProviderSearchMods
import knitty.core.application.SearchRequest
import knitty.core.application.SearchSource
import knitty.core.model.*
import knitty.core.ports.ModProvider
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class SearchModsTest {
    @Test
    fun routesEachGameToItsConfiguredProviderAndProviderGameId() = runTest {
        val calls = mutableListOf<String>()
        val first = ModProvider { game, query, offset ->
            calls += "first:${game.value}:$query:$offset"
            SearchOutcome.Found(SearchPage(emptyList(), offset, 0))
        }
        val second = ModProvider { game, query, offset ->
            calls += "second:${game.value}:$query:$offset"
            SearchOutcome.Found(SearchPage(emptyList(), offset, 0))
        }
        val search = ProviderSearchMods(
            mapOf(
                GameId("core-keeper") to SearchSource(first, ProviderGameId("corekeeper")),
                GameId("valheim") to SearchSource(second, ProviderGameId("valheim-community")),
            ),
        )

        search.search(SearchRequest(GameId("valheim"), " storage ", 20))
        search.search(SearchRequest(GameId("core-keeper"), "boxes"))

        assertEquals(listOf("second:valheim-community:storage:20", "first:corekeeper:boxes:0"), calls)
    }

    @Test
    fun mapsGameAndNormalizesQueryBeforeCallingProvider() = runTest {
        val expected = SearchOutcome.Found(SearchPage(emptyList(), 20, 20))
        val provider = ModProvider { game, query, offset ->
            assertEquals(ProviderGameId("corekeeper"), game)
            assertEquals("storage boxes", query)
            assertEquals(20, offset)
            expected
        }
        val search = ProviderSearchMods(
            mapOf(GameId("core-keeper") to SearchSource(provider, ProviderGameId("corekeeper"))),
        )
        assertEquals(expected, search.search(SearchRequest(GameId("core-keeper"), "  storage boxes  ", 20)))
    }

    @Test
    fun rejectsInvalidRequestsWithoutCallingProvider() = runTest {
        val provider = ModProvider { _, _, _ -> error("Provider must not be called") }
        val search =
            ProviderSearchMods(mapOf(GameId("core-keeper") to SearchSource(provider, ProviderGameId("corekeeper"))))
        assertEquals(
            SearchOutcome.Failed(SearchFailure.UnsupportedGame(GameId("unknown"))),
            search.search(SearchRequest(GameId("unknown"), "storage")),
        )
        assertEquals(
            SearchOutcome.Failed(SearchFailure.EmptyQuery),
            search.search(SearchRequest(GameId("core-keeper"), "  ")),
        )
        assertEquals(
            SearchOutcome.Failed(SearchFailure.InvalidOffset),
            search.search(SearchRequest(GameId("core-keeper"), "storage", -1)),
        )
    }

    @Test
    fun preservesActionableProviderFailures() = runTest {
        val expected = SearchOutcome.Failed(SearchFailure.Provider("modio", ProviderFailure.RateLimited(30)))
        val provider = ModProvider { _, _, _ -> expected }
        val search =
            ProviderSearchMods(mapOf(GameId("core-keeper") to SearchSource(provider, ProviderGameId("corekeeper"))))
        assertEquals(expected, search.search(SearchRequest(GameId("core-keeper"), "storage")))
    }
}
