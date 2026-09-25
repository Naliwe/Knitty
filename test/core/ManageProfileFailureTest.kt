package knitty.core

import knitty.core.application.DefaultManageProfile
import knitty.core.model.*
import knitty.core.ports.ArtifactSink
import knitty.core.ports.LocateGameInstallation
import knitty.core.ports.ProfileDeployment
import knitty.core.ports.ProfileStore
import knitty.profileGame
import knitty.sampleSync
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class ManageProfileFailureTest {
    @Test
    fun storageFailureStopsEveryPlanningOperationBeforeResolutionOrInspection() = runTest {
        val failure = ProfileResult.Failed(ProfileFailure.RecoveryRequired)
        val service = service(readSnapshot = { failure })

        assertEquals(failure, service.add(profileGame, PackageReference("modio", "1")))
        assertEquals(failure, service.remove(profileGame, PackageId("modio", "1")))
        assertEquals(failure, service.lock(profileGame))
        assertEquals(failure, service.planUpdate(profileGame))
        assertEquals(failure, service.planSync(profileGame))
    }

    @Test
    fun installationAndInspectionFailuresArePreservedWithoutDownloading() = runTest {
        val missing = service(
            locate = LocateGameInstallation { _, _ ->
                InstallationOutcome.Failed(ApplyFailure.InstallationNotFound)
            },
        )
        assertEquals(
            ProfileResult.Failed(ProfileFailure.Deployment(ApplyFailure.InstallationNotFound)),
            missing.planSync(profileGame),
        )

        val changed = service(
            locate = LocateGameInstallation { _, _ -> InstallationOutcome.Found(sampleSync.installation) },
            inspectInstallation = { ProfileResult.Failed(ProfileFailure.ModifiedInstallation) },
        )
        assertEquals(ProfileResult.Failed(ProfileFailure.ModifiedInstallation), changed.planSync(profileGame))
    }

    @Test
    fun cancellationFromProfileReadsIsNotConvertedToABusinessFailure() = runTest {
        val cancellation = CancellationException("Read cancelled")
        val service = service(readSnapshot = { throw cancellation })

        val actual = assertFailsWith<CancellationException> { service.lock(profileGame) }

        assertSame(cancellation, actual)
    }

    private fun service(
        readSnapshot: suspend () -> ProfileResult<ProfileSnapshot> = { ProfileResult.Success(sampleSync.source) },
        locate: LocateGameInstallation = LocateGameInstallation { _, _ -> error("Unexpected location") },
        inspectInstallation: suspend () -> ProfileResult<InstallationSnapshot> = { error("Unexpected inspection") },
    ): DefaultManageProfile = DefaultManageProfile(
        store = object : ProfileStore {
            override suspend fun read(): ProfileResult<ProfileSnapshot> = readSnapshot()
            override suspend fun save(plan: ProfileEditPlan): ProfileResult<Unit> = error("Planning cannot save")
            override suspend fun <T> withSnapshot(
                snapshot: ProfileSnapshot,
                action: suspend () -> ProfileResult<T>,
            ): ProfileResult<T> = error("Planning cannot apply")
        },
        planner = { error("Unexpected resolution") },
        locate = locate,
        deployment = object : ProfileDeployment {
            override suspend fun inspect(
                installation: GameInstallation,
                profileId: String,
            ): ProfileResult<InstallationSnapshot> = inspectInstallation()

            override suspend fun apply(
                plan: SyncPlan,
                progress: ProgressSink,
                download: suspend (Change.Install, ArtifactSink) -> DownloadOutcome,
            ): ProfileResult<Unit> = error("Planning cannot deploy")
        },
        download = { _, _, _ -> error("Planning cannot download") },
        games = mapOf(profileGame to ProviderGameId("corekeeper")),
        newProfileId = { error("Unexpected profile creation") },
    )
}
