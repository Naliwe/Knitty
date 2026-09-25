package knitty.core

import knitty.core.application.DefaultApplyChangePlan
import knitty.core.model.*
import knitty.core.ports.ArtifactSink
import knitty.core.ports.DownloadArtifact
import knitty.core.ports.GameDeployment
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class ApplyChangePlanTest {
    private val game = GameId("core-keeper")
    private val installation = GameInstallation(game, "/game", "/game/mods")
    private val change = Change.Install(
        PackageId("modio", "123"), "Mod",
        PackageArtifact(PackageVersion(ArtifactId("456"), "1"), "mod.zip", 3, null),
    )

    @Test
    fun passesPinnedArtifactToProviderAndDeploymentAndPreservesFailures() = runTest {
        var downloaded = false
        val downloader = DownloadArtifact { providerGame, actual, sink ->
            assertEquals(ProviderGameId("corekeeper"), providerGame)
            assertEquals(change, actual)
            sink.write(byteArrayOf(1, 2, 3), 3)
            downloaded = true
            DownloadOutcome.Failed(ApplyFailure.DownloadFailed)
        }
        val deployment = object : GameDeployment {
            override suspend fun install(
                installation: GameInstallation,
                change: Change.Install,
                download: suspend (ArtifactSink) -> DownloadOutcome,
            ): ApplyOutcome {
                assertEquals(this@ApplyChangePlanTest.installation, installation)
                assertEquals(this@ApplyChangePlanTest.change, change)
                val result = download(
                    ArtifactSink { bytes, count ->
                        assertEquals(3, count)
                        assertContentEquals(byteArrayOf(1, 2, 3), bytes)
                    },
                )
                return ApplyOutcome.Failed(assertIs<DownloadOutcome.Failed>(result).failure)
            }
        }
        val apply = DefaultApplyChangePlan(downloader, deployment, "modio", mapOf(game to ProviderGameId("corekeeper")))

        assertEquals(
            ApplyOutcome.Failed(ApplyFailure.DownloadFailed),
            apply.apply(ChangePlan(game, listOf(change), change.id), installation),
        )
        assertTrue(downloaded)
    }

    @Test
    fun refusesWrongGameProviderAndMultiModPlansBeforeSideEffects() = runTest {
        val deployment = object : GameDeployment {
            override suspend fun install(
                installation: GameInstallation,
                change: Change.Install,
                download: suspend (ArtifactSink) -> DownloadOutcome,
            ): ApplyOutcome =
                error("Must not deploy")
        }
        val apply = DefaultApplyChangePlan(
            { _, _, _ -> error("Must not download") }, deployment, "modio",
            mapOf(game to ProviderGameId("corekeeper")),
        )
        for (plan in listOf(
            ChangePlan(GameId("unsupported"), listOf(change), change.id),
            ChangePlan(game, emptyList(), change.id),
            ChangePlan(game, listOf(change, change), change.id),
            ChangePlan(game, listOf(change.copy(id = PackageId("other", "123"))), change.id),
        )) {
            assertEquals(ApplyOutcome.Failed(ApplyFailure.InvalidPlan), apply.apply(plan, installation))
        }
        assertEquals(
            ApplyOutcome.Failed(ApplyFailure.InvalidPlan),
            apply.apply(ChangePlan(game, listOf(change), change.id), installation.copy(game = GameId("other"))),
        )
    }
}
