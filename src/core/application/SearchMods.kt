package knitty.core.application

import knitty.core.model.GameId
import knitty.core.model.SearchOutcome

data class SearchRequest(
    val game: GameId,
    val query: String,
    val offset: Int = 0,
)

fun interface SearchMods {
    suspend fun search(request: SearchRequest): SearchOutcome
}
