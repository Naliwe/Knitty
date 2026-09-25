package knitty.core.ports

import knitty.core.model.ProviderGameId
import knitty.core.model.SearchOutcome

fun interface ModProvider {
    suspend fun search(game: ProviderGameId, query: String, offset: Int): SearchOutcome
}
