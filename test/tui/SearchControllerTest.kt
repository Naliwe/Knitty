package knitty.tui

import knitty.core.application.SearchRequest
import knitty.core.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.*
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalCoroutinesApi::class)
class SearchControllerTest {
    private val game = GameId("core-keeper")
    private fun mod(id: Int) =
        ModSearchResult(PackageId("modio", "$id"), "mod-$id", "Mod $id", "Summary", "Author", null, "url")

    @Test
    fun invokesSamePortAndSupportsSelectionAndPageNavigation() = runTest {
        val requests = mutableListOf<SearchRequest>()
        val controller = SearchController(
            game,
            {
                requests += it
                SearchOutcome.Found(SearchPage(listOf(mod(it.offset), mod(it.offset + 1)), it.offset, 4))
            },
            this,
        )
        controller.search("storage")
        assertTrue(controller.state.value.loading)
        runCurrent()
        assertEquals(SearchRequest(game, "storage"), requests.single())
        controller.select(1)
        assertEquals("Mod 1", controller.state.value.selected?.name)
        controller.select(100)
        assertEquals(1, controller.state.value.selection)
        controller.nextPage()
        runCurrent()
        assertEquals(SearchRequest(game, "storage", 2), requests.last())
        assertEquals("Mod 2", controller.state.value.selected?.name)
        controller.nextPage()
        assertEquals(2, requests.size)
        controller.previousPage()
        runCurrent()
        assertEquals(SearchRequest(game, "storage", 0), requests.last())
    }

    @Test
    fun lateResultsCannotReplaceNewerSearchAndCloseCancelsWork() = runTest {
        var cancelled = false
        val controller = SearchController(
            game,
            {
                if (it.query == "old") {
                    withContext(NonCancellable) { delay(100.milliseconds) }
                }
                if (it.query == "pending") {
                    try {
                        awaitCancellation()
                    } finally {
                        cancelled = true
                    }
                }
                SearchOutcome.Found(SearchPage(listOf(mod(if (it.query == "old") 1 else 2)), 0, 1))
            },
            this,
        )
        controller.search("old")
        runCurrent()
        controller.search("new")
        advanceUntilIdle()
        assertEquals("new", controller.state.value.query)
        assertEquals("Mod 2", controller.state.value.selected?.name)
        controller.search("pending")
        runCurrent()
        controller.close()
        runCurrent()
        assertTrue(cancelled)
    }

    @Test
    fun errorsClearResultsAndNewSearchCanRecover() = runTest {
        val controller = SearchController(
            game,
            {
                if (it.query == "fail") SearchOutcome.Failed(
                    SearchFailure.Provider(
                        "modio",
                        ProviderFailure.Unavailable,
                    ),
                )
                else SearchOutcome.Found(SearchPage(emptyList(), 0, 0))
            },
            this,
        )
        controller.search("fail")
        runCurrent()
        assertEquals(SearchFailure.Provider("modio", ProviderFailure.Unavailable), controller.state.value.failure)
        assertNull(controller.state.value.selected)
        assertFalse(controller.state.value.loading)
        controller.search("retry")
        runCurrent()
        assertNull(controller.state.value.failure)
        assertTrue(controller.state.value.page!!.mods.isEmpty())
    }
}
