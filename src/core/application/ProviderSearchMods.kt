package knitty.core.application

import knitty.core.model.GameId
import knitty.core.model.ProviderGameId
import knitty.core.model.SearchFailure
import knitty.core.model.SearchOutcome
import knitty.core.ports.ModProvider

data class SearchSource(val provider: ModProvider, val game: ProviderGameId)

class ProviderSearchMods(private val sources: Map<GameId, SearchSource>) : SearchMods {
    override suspend fun search(request: SearchRequest): SearchOutcome {
        val source = sources[request.game]
            ?: return SearchOutcome.Failed(SearchFailure.UnsupportedGame(request.game))

        val query = request.query.trim()
        if (query.isEmpty()) return SearchOutcome.Failed(SearchFailure.EmptyQuery)
        if (request.offset < 0) return SearchOutcome.Failed(SearchFailure.InvalidOffset)

        return source.provider.search(source.game, query, request.offset)
    }
}
