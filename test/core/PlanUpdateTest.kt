package knitty.core

import knitty.core.application.DefaultManageProfile
import knitty.core.application.DefaultPlanInstall
import knitty.core.model.*
import knitty.core.ports.ArtifactSink
import knitty.core.ports.InstallCandidateOutcome
import knitty.core.ports.ProfileDeployment
import knitty.core.ports.ProfileStore
import knitty.profileGame
import knitty.profilePackage
import knitty.sampleLock
import knitty.sampleProfile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class PlanUpdateTest {
    @Test
    fun catalogOnlyGamesCannotPlanProfileChanges() = runTest {
        val fixture = UpdateFixture { error("Unsupported games must not resolve packages") }
        val game = GameId("valheim")
        val expected = ProfileResult.Failed(ProfileFailure.Planning(PlanInstallFailure.UnsupportedGame(game)))

        assertTrue(fixture.service.supports(profileGame))
        assertFalse(fixture.service.supports(game))
        assertEquals(expected, fixture.service.add(game, PackageReference("thunderstore", "Author-Mod")))
        assertEquals(expected, fixture.service.remove(game, PackageId("thunderstore", "Author-Mod")))
        assertEquals(expected, fixture.service.lock(game))
        assertEquals(expected, fixture.service.planUpdate(game))
        assertEquals(expected, fixture.service.planSync(game))
        assertEquals(sampleLock, fixture.snapshot.lock)
    }

    @Test
    fun plansPublishedFileChangesWithoutWritingOrComparingVersionLabels() = runTest {
        for (label in listOf("1.0", "0.1", null)) {
            val replacement = profilePackage.copy(
                artifact = profilePackage.artifact.copy(version = PackageVersion(ArtifactId("9"), label)),
            )
            val fixture = UpdateFixture { replacement }

            val plan = fixture.service.planUpdate(profileGame).value()

            assertEquals(sampleProfile, plan.profile)
            assertEquals(fixture.snapshot, plan.before)
            assertEquals(listOf(PackageUpdate(profilePackage, replacement)), plan.updates)
            assertEquals(listOf(PackageReference("modio", "1")), fixture.requests)
            assertEquals(sampleLock, fixture.snapshot.lock)
        }
    }

    @Test
    fun sameFilePreservesExactPinDespiteNameOrLabelChanges() = runTest {
        val fixture = UpdateFixture {
            profilePackage.copy(
                name = "Renamed",
                artifact = profilePackage.artifact.copy(version = PackageVersion(ArtifactId("10"), "relabelled")),
            )
        }

        val plan = fixture.service.planUpdate(profileGame).value()

        assertEquals(sampleLock, plan.lock)
        assertTrue(plan.updates.isEmpty())
    }

    @Test
    fun missingOrMismatchedLocksRequireExplicitRepairBeforeAnyResolution() = runTest {
        val cases = listOf(
            ProfileSnapshot(null, null, "missing") to ProfileFailure.MissingProfile,
            ProfileSnapshot(sampleProfile, null, "missing-lock") to ProfileFailure.LockOutOfDate,
            ProfileSnapshot(sampleProfile, sampleLock.copy(packages = emptyList()), "incomplete") to
                ProfileFailure.LockOutOfDate,
            ProfileSnapshot(sampleProfile, sampleLock.copy(game = GameId("other")), "wrong-lock") to
                ProfileFailure.LockOutOfDate,
            ProfileSnapshot(sampleProfile.copy(game = GameId("other")), sampleLock, "wrong-game") to
                ProfileFailure.WrongGame,
        )
        for ((snapshot, failure) in cases) {
            val fixture = UpdateFixture { error("Must validate before resolving") }
            fixture.snapshot = snapshot

            assertEquals(ProfileResult.Failed(failure), fixture.service.planUpdate(profileGame))
            assertTrue(fixture.requests.isEmpty())
        }
    }

    @Test
    fun emptyProfileNeedsNoProviderCalls() = runTest {
        val fixture = UpdateFixture { error("No packages to resolve") }
        fixture.snapshot = ProfileSnapshot(
            sampleProfile.copy(packages = emptyList()),
            sampleLock.copy(packages = emptyList()),
            "empty",
        )

        assertTrue(fixture.service.planUpdate(profileGame).value().updates.isEmpty())
        assertTrue(fixture.requests.isEmpty())
    }

    @Test
    fun laterResolutionFailureNeverPublishesPartialUpdatesAndCancellationPropagates() = runTest {
        val second = profilePackage.copy(id = PackageId("modio", "2"))
        val fixture = UpdateFixture { id ->
            if (id.value == "2") null else profilePackage.copy(
                artifact = profilePackage.artifact.copy(version = PackageVersion(ArtifactId("11"), "2.0")),
            )
        }
        fixture.snapshot = ProfileSnapshot(
            sampleProfile.copy(packages = listOf(profilePackage.id, second.id)),
            sampleLock.copy(packages = listOf(profilePackage, second)),
            "two",
        )
        val before = fixture.snapshot

        assertEquals(
            ProfileResult.Failed(ProfileFailure.Planning(PlanInstallFailure.NoPublishedFile)),
            fixture.service.planUpdate(profileGame),
        )
        assertEquals(2, fixture.requests.size)
        assertEquals(before, fixture.snapshot)

        val cancelled = UpdateFixture { throw CancellationException() }
        assertFailsWith<CancellationException> { cancelled.service.planUpdate(profileGame) }
    }

    @Test
    fun newlyDeclaredDependencyCyclesBlockUpdate() = runTest {
        val fixture = UpdateFixture { profilePackage }
        fixture.hasDependencies = true

        assertEquals(
            ProfileResult.Failed(
                ProfileFailure.Planning(
                    PlanInstallFailure.DependencyCycle(
                        listOf(
                            profilePackage.id,
                            profilePackage.id,
                        ),
                    ),
                ),
            ),
            fixture.service.planUpdate(profileGame),
        )
    }
}

private class UpdateFixture(candidate: (PackageReference) -> LockedPackage?) {
    var snapshot = ProfileSnapshot(sampleProfile, sampleLock, "before")
    var hasDependencies = false
    val requests = mutableListOf<PackageReference>()
    private val games = mapOf(profileGame to ProviderGameId("corekeeper"))
    private val store = object : ProfileStore {
        override suspend fun read() = ProfileResult.Success(snapshot)
        override suspend fun save(plan: ProfileEditPlan): ProfileResult<Unit> = error("Planning must not save")
        override suspend fun <T> withSnapshot(
            snapshot: ProfileSnapshot,
            action: suspend () -> ProfileResult<T>,
        ): ProfileResult<T> = error("Planning must not apply")
    }
    private val deployment = object : ProfileDeployment {
        override suspend fun inspect(
            installation: GameInstallation,
            profileId: String,
        ): ProfileResult<InstallationSnapshot> =
            error("Updates do not inspect local installations")

        override suspend fun apply(
            plan: SyncPlan,
            progress: ProgressSink,
            download: suspend (Change.Install, ArtifactSink) -> DownloadOutcome,
        ): ProfileResult<Unit> = error("Planning must not apply")
    }
    val service = DefaultManageProfile(
        store,
        DefaultPlanInstall(
            { _, reference ->
                requests += reference
                val item = candidate(reference)
                InstallCandidateOutcome.Found(
                    InstallCandidate(
                        PackageId(reference.provider, reference.value),
                        item?.name ?: "Unavailable",
                        item?.artifact,
                        if (hasDependencies) listOf(profilePackage.id) else item?.dependencies.orEmpty(),
                    ),
                )
            },
            "modio",
            games,
        ),
        { _, _ -> error("Updates do not locate installations") },
        deployment,
        { _, _, _ -> error("Planning must not download") },
        games,
        newProfileId = { error("Updates preserve profile identity") },
    )
}

private fun <T> ProfileResult<T>.value(): T = assertIs<ProfileResult.Success<T>>(this).value
