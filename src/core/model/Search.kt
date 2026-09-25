package knitty.core.model

@JvmInline
value class GameId(val value: String)

@JvmInline
value class ProviderGameId(val value: String)

data class PackageId(val provider: String, val value: String)

data class ModSearchResult(
    val id: PackageId,
    val slug: String,
    val name: String,
    val summary: String,
    val author: String,
    val version: String?,
    val pageUrl: String,
)

data class SearchPage(
    val mods: List<ModSearchResult>,
    val offset: Int,
    val total: Int,
) {
    val nextOffset: Int? get() = (offset + mods.size).takeIf { mods.isNotEmpty() && it < total }
}

sealed interface SearchFailure {
    data class UnsupportedGame(val game: GameId) : SearchFailure
    data object EmptyQuery : SearchFailure
    data object InvalidOffset : SearchFailure
    data class Provider(val provider: String, val failure: ProviderFailure) : SearchFailure
}

sealed interface SearchOutcome {
    data class Found(val page: SearchPage) : SearchOutcome
    data class Failed(val failure: SearchFailure) : SearchOutcome
}
