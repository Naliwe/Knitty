package knitty.integration

import knitty.core.application.DefaultManageProfile
import knitty.core.application.DefaultPlanInstall
import knitty.core.model.*
import knitty.games.corekeeper.CoreKeeperProfileDeployment
import knitty.profiles.FileProfileStore
import knitty.providers.modio.ModioProvider
import knitty.providers.providerHttpClient
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteIfExists
import kotlin.io.path.listDirectoryEntries
import kotlin.test.*

@EnabledIfEnvironmentVariable(named = "KNITTY_LIVE_TESTS", matches = "1")
class ModioUpdateLiveTest {
    @Test
    fun comparesTemporaryProfilePinsWithRealPublishedFileWithoutDeployment() = runBlocking {
        val apiKey = System.getenv("KNITTY_MODIO_API_KEY")
        val apiPath = System.getenv("KNITTY_MODIO_API_PATH")
        val reference = System.getenv("KNITTY_LIVE_PACKAGE")
        assertFalse(apiKey.isNullOrBlank(), "Set KNITTY_MODIO_API_KEY before running live tests")
        assertFalse(apiPath.isNullOrBlank(), "Set KNITTY_MODIO_API_PATH before running live tests")
        assertFalse(reference.isNullOrBlank(), "Set KNITTY_LIVE_PACKAGE to a dependency-free Core Keeper mod")

        val directory = createTempDirectory("knitty-live-update-")
        try {
            providerHttpClient().use { client ->
                val provider = ModioProvider(client, apiKey, apiPath)
                val game = GameId("core-keeper")
                val games = mapOf(game to ProviderGameId("corekeeper"))
                val store = FileProfileStore(directory)
                val service = DefaultManageProfile(
                    store,
                    DefaultPlanInstall(provider, "modio", games),
                    { _, _ -> error("Update planning must not locate a game") },
                    CoreKeeperProfileDeployment(),
                    { _, _, _ -> error("Update planning must not download") },
                    games,
                    newProfileId = { "live-update-test" },
                )
                val initial = service.add(game, PackageReference("modio", reference)).value()
                service.save(initial).value()
                assertTrue(service.planUpdate(game).value().updates.isEmpty())

                val before = store.read().value()
                val published = initial.lock.packages.single()
                // A synthetic previous pin exercises the diff without requiring an actual new release.
                val previousId = if (published.artifact.version.artifactId.value == "1") "2" else "1"
                val previous = published.copy(
                    artifact = published.artifact.copy(
                        version = PackageVersion(
                            ArtifactId(previousId),
                            "test-baseline",
                        ),
                    ),
                )
                service.save(initial.copy(before = before, lock = initial.lock.copy(packages = listOf(previous))))
                    .value()
                val baseline = store.read().value()

                val update = service.planUpdate(game).value()

                assertEquals(baseline, store.read().value())
                assertEquals(listOf(PackageUpdate(previous, published)), update.updates)
                service.save(update).value()
                assertEquals(initial.lock, store.read().value().lock)
            }
        } finally {
            directory.listDirectoryEntries().forEach { it.deleteIfExists() }
            directory.deleteIfExists()
        }
    }
}

private fun <T> ProfileResult<T>.value(): T = assertIs<ProfileResult.Success<T>>(this).value
