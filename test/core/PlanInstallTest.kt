package knitty.core

import knitty.core.application.DefaultPlanInstall
import knitty.core.application.PlanInstallRequest
import knitty.core.model.*
import knitty.core.ports.InstallCandidateOutcome
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class PlanInstallTest {
    private val game = GameId("core-keeper")
    private val games = mapOf(game to ProviderGameId("corekeeper"))
    private val artifact = PackageArtifact(
        PackageVersion(ArtifactId("456"), "not-semver"),
        "storage.zip",
        1024,
        Checksum(ChecksumAlgorithm.MD5, "a".repeat(32)),
    )
    private val candidate = InstallCandidate(PackageId("modio", "123"), "Storage", artifact)

    @Test
    fun plansCanonicalPackageAndExactPublishedArtifact() = runTest {
        val planner = DefaultPlanInstall(
            { actualGame, reference ->
                assertEquals(ProviderGameId("corekeeper"), actualGame)
                assertEquals(PackageReference("modio", "storage"), reference)
                InstallCandidateOutcome.Found(candidate)
            },
            "modio", games,
        )

        val outcome = planner.plan(PlanInstallRequest(game, PackageReference("modio", "storage")))

        val plan = assertIs<PlanInstallOutcome.Planned>(outcome).plan
        assertEquals(game, plan.game)
        assertEquals(listOf(Change.Install(candidate.id, "Storage", artifact)), plan.changes)
        assertEquals(1024, plan.downloadBytes)
    }

    @Test
    fun rejectsUnsupportedInputWithoutProviderCalls() = runTest {
        val planner =
            DefaultPlanInstall({ _, _ -> error("Must not call provider") }, "modio", games)
        val cases = listOf(
            PlanInstallRequest(GameId("other"), PackageReference("modio", "1")) to
                PlanInstallFailure.UnsupportedGame(GameId("other")),
            PlanInstallRequest(game, PackageReference("other", "1")) to
                PlanInstallFailure.UnsupportedProvider("other"),
            PlanInstallRequest(game, PackageReference("modio", " ")) to
                PlanInstallFailure.InvalidPackageReference,
        )

        for ((request, failure) in cases) {
            assertEquals(PlanInstallOutcome.Failed(failure), planner.plan(request))
        }
    }

    @Test
    fun dependenciesAndMissingFilesNeverProduceAnIncompletePlan() = runTest {
        val cases = listOf(
            candidate.copy(dependencies = listOf(candidate.id)) to PlanInstallFailure.DependencyCycle(
                listOf(
                    candidate.id,
                    candidate.id,
                ),
            ),
            candidate.copy(artifact = null) to PlanInstallFailure.NoPublishedFile,
        )
        for ((value, failure) in cases) {
            val planner =
                DefaultPlanInstall({ _, _ -> InstallCandidateOutcome.Found(value) }, "modio", games)

            val outcome = planner.plan(PlanInstallRequest(game, PackageReference("modio", "123")))

            assertEquals(PlanInstallOutcome.Failed(failure), outcome)
        }
    }

    @Test
    fun unlabelledVersionsRemainPinnedAndProviderFailuresRemainActionable() = runTest {
        val unlabelled = candidate.copy(artifact = artifact.copy(version = PackageVersion(ArtifactId("456"), null)))
        val planner = DefaultPlanInstall(
            { _, _ -> InstallCandidateOutcome.Found(unlabelled) },
            "modio",
            games,
        )

        val outcome = planner.plan(PlanInstallRequest(game, PackageReference("modio", "123")))

        val change = assertIs<Change.Install>(assertIs<PlanInstallOutcome.Planned>(outcome).plan.changes.single())
        assertEquals(ArtifactId("456"), change.artifact.version.artifactId)
        assertNull(change.artifact.version.label)

        val failure = PlanInstallFailure.Provider(ProviderFailure.RateLimited(30))
        val failingPlanner =
            DefaultPlanInstall({ _, _ -> InstallCandidateOutcome.Failed(failure) }, "modio", games)
        assertEquals(
            PlanInstallOutcome.Failed(failure),
            failingPlanner.plan(PlanInstallRequest(game, PackageReference("modio", "123"))),
        )
    }
}
