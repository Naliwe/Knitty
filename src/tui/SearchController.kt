package knitty.tui

import knitty.core.application.SearchMods
import knitty.core.application.SearchRequest
import knitty.core.model.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class SearchState(
    val query: String = "",
    val loading: Boolean = false,
    val page: SearchPage? = null,
    val failure: SearchFailure? = null,
    val selection: Int = 0,
    val previousOffsets: List<Int> = emptyList(),
) {
    val selected: ModSearchResult? get() = page?.mods?.getOrNull(selection)
}

// All actions are dispatched on the TUI event loop; network calls suspend without blocking input.
class SearchController(
    val game: GameId,
    private val searchMods: SearchMods,
    private val scope: CoroutineScope,
) {
    private val mutableState = MutableStateFlow(SearchState())
    val state = mutableState.asStateFlow()

    private var searchJob: Job? = null

    fun search(query: String) = load(query, 0, emptyList())

    fun nextPage() {
        val current = state.value
        if (current.loading) return

        val page = current.page
            ?: return
        val offset = page.nextOffset
            ?: return

        load(current.query, offset, current.previousOffsets + page.offset)
    }

    fun previousPage() {
        val current = state.value
        if (current.loading) return

        val offset = current.previousOffsets.lastOrNull()
            ?: return

        load(current.query, offset, current.previousOffsets.dropLast(1))
    }

    fun select(delta: Int) {
        val current = state.value
        val last = current.page?.mods?.lastIndex
            ?: return
        if (last < 0) return

        mutableState.value = current.copy(selection = (current.selection + delta).coerceIn(0, last))
    }

    fun close() {
        searchJob?.cancel()
    }

    private fun load(query: String, offset: Int, previousOffsets: List<Int>) {
        searchJob?.cancel()
        mutableState.value = SearchState(query = query, loading = true)

        searchJob = scope.launch {
            val outcome = searchMods.search(SearchRequest(game, query, offset))
            ensureActive()

            mutableState.value = when (outcome) {
                is SearchOutcome.Found -> SearchState(query, page = outcome.page, previousOffsets = previousOffsets)
                is SearchOutcome.Failed -> SearchState(query, failure = outcome.failure)
            }
        }
    }
}
