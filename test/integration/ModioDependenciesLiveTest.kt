package knitty.integration

import knitty.core.application.DefaultManageProfile
import knitty.core.application.DefaultPlanInstall
import knitty.core.model.*
import knitty.games.corekeeper.CoreKeeperInstallation
import knitty.games.corekeeper.CoreKeeperProfileDeployment
import knitty.profiles.FileProfileStore
import knitty.providers.modio.ModioProvider
import knitty.providers.providerHttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteIfExists
import kotlin.io.path.writeText
import kotlin.test.*

@EnabledIfEnvironmentVariable(named = "KNITTY_LIVE_DEPENDENCIES", matches = "1")
class ModioDependenciesLiveTest {
    @Test
    fun resolvesDownloadsAndDeploysRealDependencyGraphIntoTemporaryInstallation() = runBlocking {
        val key = System.getenv("KNITTY_MODIO_API_KEY")
        val endpoint = System.getenv("KNITTY_MODIO_API_PATH")
        val reference = System.getenv("KNITTY_LIVE_DEPENDENCY_PACKAGE")
        assertFalse(key.isNullOrBlank(), "Set KNITTY_MODIO_API_KEY")
        assertFalse(endpoint.isNullOrBlank(), "Set KNITTY_MODIO_API_PATH")
        assertFalse(reference.isNullOrBlank(), "Choose a native Core Keeper mod with dependencies")
        val root = createTempDirectory("knitty-live-dependencies-")
        try {
            val gameDirectory = root.resolve("game").createDirectories()
            gameDirectory.resolve("CoreKeeper").writeText("")
            gameDirectory.resolve("CoreKeeper_Data/StreamingAssets").createDirectories()
            providerHttpClient().use { client ->
                val provider = ModioProvider(client, key, endpoint)
                val game = GameId("core-keeper")
                val games = mapOf(game to ProviderGameId("corekeeper"))
                val store = FileProfileStore(root.resolve("profile"))
                val deployment = CoreKeeperProfileDeployment()
                val service = DefaultManageProfile(
                    store, DefaultPlanInstall(provider, "modio", games), CoreKeeperInstallation(),
                    deployment, provider, games,
                ) { "live-dependencies" }
                val edit = service.add(game, PackageReference("modio", reference)).value()
                assertEquals(1, edit.profile.packages.size)
                assertTrue(edit.lock.packages.size > 1, "Choose a mod that declares dependencies")
                assertTrue(edit.lock.isCompleteFor(edit.profile))
                service.save(edit).value()
                val sync = service.planSync(game, gameDirectory.toString()).value()
                service.apply(sync).value()

                val installed = deployment.inspect(sync.installation, edit.profile.id).value()
                assertEquals(edit.lock.packages.map { it.id }.toSet(), installed.managed)
                assertTrue(service.planSync(game, gameDirectory.toString()).value().install.isEmpty())
                println("Live dependency check: ${installed.packages.size} pinned mods deployed into a temporary installation.")
            }
        } finally {
            withContext(Dispatchers.IO) {
                Files.walk(root)
                    .use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { it.deleteIfExists() } }
            }
        }
    }
}

private fun <T> ProfileResult<T>.value(): T = assertIs<ProfileResult.Success<T>>(this).value
