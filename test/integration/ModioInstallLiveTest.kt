package knitty.integration

import knitty.core.application.DefaultApplyChangePlan
import knitty.core.application.DefaultPlanInstall
import knitty.core.application.PlanInstallRequest
import knitty.core.model.*
import knitty.games.corekeeper.CoreKeeperDeployment
import knitty.games.corekeeper.CoreKeeperInstallation
import knitty.providers.modio.ModioProvider
import knitty.providers.providerHttpClient
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.nio.file.Files
import kotlin.io.path.*
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue

@EnabledIfEnvironmentVariable(named = "KNITTY_LIVE_INSTALL", matches = "1")
class ModioInstallLiveTest {
    @Test
    fun downloadsOneRealModAndDeploysIntoTemporaryGameLayout() = runBlocking {
        val reference = requireNotNull(System.getenv("KNITTY_LIVE_PACKAGE")) {
            "Set KNITTY_LIVE_PACKAGE to a dependency-free native Core Keeper mod ID or slug"
        }
        val root = createTempDirectory("knitty-live-install-")
        try {
            root.resolve("CoreKeeper").writeText("")
            root.resolve("CoreKeeper_Data/StreamingAssets").createDirectories()
            providerHttpClient().use { client ->
                val provider =
                    ModioProvider(client, System.getenv("KNITTY_MODIO_API_KEY"), System.getenv("KNITTY_MODIO_API_PATH"))
                val game = GameId("core-keeper")
                val games = mapOf(game to ProviderGameId("corekeeper"))
                val planner = DefaultPlanInstall(provider, "modio", games)
                val planned = planner.plan(PlanInstallRequest(game, PackageReference("modio", reference)))
                val plan = assertIs<PlanInstallOutcome.Planned>(planned).plan
                val located = CoreKeeperInstallation().locate(game, root.toString())
                val installation = assertIs<InstallationOutcome.Found>(located).installation
                val apply = DefaultApplyChangePlan(provider, CoreKeeperDeployment(), "modio", games)

                val result = assertIs<ApplyOutcome.Installed>(apply.apply(plan, installation))

                assertTrue(Path(result.directory).resolve("ModManifest.json").isRegularFile())
                assertTrue(Path(result.directory).resolve(".knitty-ownership.json").isRegularFile())
            }
        } finally {
            Files.walk(root).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach { it.deleteIfExists() }
            }
        }
    }
}
