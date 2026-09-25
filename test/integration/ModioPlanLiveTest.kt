package knitty.integration

import knitty.core.application.DefaultPlanInstall
import knitty.core.application.PlanInstallRequest
import knitty.core.model.*
import knitty.providers.modio.ModioProvider
import knitty.providers.providerHttpClient
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import kotlin.test.*

@EnabledIfEnvironmentVariable(named = "KNITTY_LIVE_TESTS", matches = "1")
class ModioPlanLiveTest {
    @Test
    fun plansOnePublishedCoreKeeperMod() = runBlocking {
        val apiKey = System.getenv("KNITTY_MODIO_API_KEY")
        val apiPath = System.getenv("KNITTY_MODIO_API_PATH")
        val reference = System.getenv("KNITTY_LIVE_PACKAGE")
        assertFalse(apiKey.isNullOrBlank(), "Set KNITTY_MODIO_API_KEY before running live tests")
        assertFalse(apiPath.isNullOrBlank(), "Set KNITTY_MODIO_API_PATH before running live tests")
        assertFalse(
            reference.isNullOrBlank(),
            "Set KNITTY_LIVE_PACKAGE to a dependency-free Core Keeper mod's ID or slug",
        )

        providerHttpClient().use { client ->
            val provider = ModioProvider(client, apiKey, apiPath)
            val game = GameId("core-keeper")
            val planner = DefaultPlanInstall(provider, "modio", mapOf(game to ProviderGameId("corekeeper")))

            val outcome = planner.plan(PlanInstallRequest(game, PackageReference("modio", reference)))

            val plan = assertIs<PlanInstallOutcome.Planned>(outcome).plan
            val change = assertIs<Change.Install>(plan.changes.single())
            assertEquals(game, plan.game)
            assertEquals("modio", change.id.provider)
            assertTrue(change.artifact.version.artifactId.value.toLong() > 0)
            assertTrue(change.artifact.sizeBytes > 0)
        }
    }
}
