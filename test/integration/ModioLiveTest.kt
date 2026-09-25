package knitty.integration

import knitty.core.application.ProviderSearchMods
import knitty.core.application.SearchRequest
import knitty.core.application.SearchSource
import knitty.core.model.GameId
import knitty.core.model.ProviderGameId
import knitty.core.model.SearchOutcome
import knitty.providers.modio.ModioProvider
import knitty.providers.providerHttpClient
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

@EnabledIfEnvironmentVariable(named = "KNITTY_LIVE_TESTS", matches = "1")
class ModioLiveTest {
    @Test
    fun searchesCoreKeeperOnModio() = runBlocking {
        val key = System.getenv("KNITTY_MODIO_API_KEY")
        val apiPath = System.getenv("KNITTY_MODIO_API_PATH")
        assertFalse(key.isNullOrBlank(), "Set KNITTY_MODIO_API_KEY before running live tests")
        assertFalse(apiPath.isNullOrBlank(), "Set KNITTY_MODIO_API_PATH before running live tests")
        providerHttpClient().use { client ->
            val search = ProviderSearchMods(
                mapOf(
                    GameId("core-keeper") to SearchSource(
                        ModioProvider(client, key, apiPath),
                        ProviderGameId("corekeeper"),
                    ),
                ),
            )
            val result = assertIs<SearchOutcome.Found>(search.search(SearchRequest(GameId("core-keeper"), "storage")))
            assertTrue(result.page.mods.isNotEmpty(), "Expected real storage search results")
            assertTrue(result.page.mods.all { it.id.provider == "modio" && it.pageUrl.contains("/g/corekeeper/") })
        }
    }
}
