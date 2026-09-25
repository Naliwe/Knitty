package knitty.integration

import knitty.core.application.ProviderSearchMods
import knitty.core.application.SearchRequest
import knitty.core.application.SearchSource
import knitty.core.model.GameId
import knitty.core.model.ProviderGameId
import knitty.core.model.SearchOutcome
import knitty.providers.providerHttpClient
import knitty.providers.thunderstore.ThunderstoreProvider
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue

@EnabledIfEnvironmentVariable(named = "KNITTY_THUNDERSTORE_LIVE_TESTS", matches = "1")
class ThunderstoreLiveTest {
    @Test
    fun searchesPublicValheimCatalogWithoutCredentials() = runBlocking {
        providerHttpClient().use { client ->
            val game = GameId("valheim")
            val search = ProviderSearchMods(
                mapOf(game to SearchSource(ThunderstoreProvider(client), ProviderGameId("valheim"))),
            )

            val outcome = search.search(SearchRequest(game, "BepInExPack_Valheim"))
            val result = assertIs<SearchOutcome.Found>(outcome, outcome.toString())

            assertTrue(result.page.mods.isNotEmpty(), "Expected the Valheim BepInEx package in the live catalog")
            assertTrue(result.page.mods.all { it.id.provider == "thunderstore" && it.pageUrl.contains("/c/valheim/") })
            assertTrue(result.page.mods.any { it.id.value == "denikson-BepInExPack_Valheim" })
        }
    }
}
