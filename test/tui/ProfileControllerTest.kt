package knitty.tui

import knitty.*
import knitty.core.model.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalCoroutinesApi::class)
class ProfileControllerTest {
    @Test
    fun serverTargetIsPassedToSharedApplicationPortAndOnlyAppliedOnConfirmation() = runTest {
        var applied = false
        val service = object : ProfileFake() {
            override suspend fun planSync(
                game: GameId,
                directory: String?,
                target: String?,
                progress: ProgressSink,
            ): ProfileResult<SyncPlan> {
                assertEquals("ovh", target)
                assertEquals(null, directory)
                return ProfileResult.Success(sampleServerSync)
            }

            override suspend fun apply(plan: SyncPlan, progress: ProgressSink): ProfileResult<Unit> {
                assertEquals(sampleServerSync, plan)
                applied = true
                return ProfileResult.Success(Unit)
            }
        }
        val controller = ProfileController(profileGame, service, this, "ovh")
        controller.sync()
        runCurrent()
        assertFalse(applied)
        assertEquals(ProfileScreenState.Sync(sampleServerSync), controller.state.value)

        controller.confirm()
        runCurrent()
        assertTrue(applied)
        assertEquals(ProfileScreenState.Done(true), controller.state.value)
    }

    @Test
    fun outdatedCannotSaveUntilStagedAndUpdateCanBeCancelled() = runTest {
        var plans = 0
        var saves = 0
        val service = object : ProfileFake() {
            override suspend fun planUpdate(game: GameId): ProfileResult<ProfileEditPlan> {
                assertEquals(profileGame, game)
                plans++
                return ProfileResult.Success(sampleUpdate)
            }

            override suspend fun save(plan: ProfileEditPlan): ProfileResult<Unit> {
                assertEquals(sampleUpdate, plan)
                saves++
                return ProfileResult.Success(Unit)
            }
        }
        val controller = ProfileController(profileGame, service, this)
        controller.outdated()
        runCurrent()
        assertEquals(ProfileScreenState.Outdated(sampleUpdate), controller.state.value)

        controller.confirm()
        runCurrent()
        assertEquals(0, saves)
        controller.update()
        assertEquals(ProfileScreenState.Edit(sampleUpdate), controller.state.value)
        assertEquals(1, plans)
        controller.dismiss()
        assertEquals(0, saves)

        controller.update()
        runCurrent()
        controller.confirm()
        runCurrent()
        assertEquals(2, plans)
        assertEquals(1, saves)
        assertEquals(ProfileScreenState.Done(false), controller.state.value)
    }

    @Test
    fun unchangedUpdatesAndFailedChecksCannotBeConfirmed() = runTest {
        val unchanged = sampleUpdate.copy(lock = sampleLock)
        var fail = false
        val service = object : ProfileFake() {
            override suspend fun planUpdate(game: GameId) = if (fail) {
                ProfileResult.Failed(ProfileFailure.LockOutOfDate)
            } else {
                ProfileResult.Success(unchanged)
            }
        }
        val controller = ProfileController(profileGame, service, this)
        controller.update()
        runCurrent()
        controller.confirm()
        runCurrent()
        assertEquals(ProfileScreenState.Outdated(unchanged), controller.state.value)

        fail = true
        controller.dismiss()
        controller.update()
        runCurrent()
        controller.confirm()
        assertEquals(ProfileScreenState.Failed(ProfileFailure.LockOutOfDate), controller.state.value)
    }

    @Test
    fun stagedEditAndSyncEachRequireConfirmationThroughSameApplicationPort() = runTest {
        var saves = 0
        var applies = 0
        val service = object : ProfileFake() {
            override suspend fun add(game: GameId, reference: PackageReference): ProfileResult<ProfileEditPlan> {
                assertEquals(profileGame, game)
                assertEquals(PackageReference("modio", "1"), reference)
                return ProfileResult.Success(sampleEdit)
            }

            override suspend fun save(plan: ProfileEditPlan): ProfileResult<Unit> {
                assertEquals(sampleEdit, plan)
                saves++
                return ProfileResult.Success(Unit)
            }

            override suspend fun planSync(game: GameId, directory: String?, target: String?, progress: ProgressSink) =
                ProfileResult.Success(sampleSync)

            override suspend fun apply(plan: SyncPlan, progress: ProgressSink): ProfileResult<Unit> {
                assertEquals(sampleSync, plan)
                applies++
                progress.report(OperationProgress(ProgressStage.Uploading, "Example", 50, 100))
                delay(100.milliseconds)
                return ProfileResult.Success(Unit)
            }
        }
        val controller = ProfileController(profileGame, service, this)
        controller.add(profilePackage.id)
        runCurrent()
        assertEquals(ProfileScreenState.Edit(sampleEdit), controller.state.value)
        assertEquals(0, saves)
        controller.confirm()
        runCurrent()
        assertEquals(1, saves)
        assertEquals(0, applies)
        controller.sync()
        runCurrent()
        assertEquals(ProfileScreenState.Sync(sampleSync), controller.state.value)
        controller.confirm()
        runCurrent()
        controller.confirm()
        controller.dismiss()
        assertEquals(ProfileScreenState.Applying, controller.state.value)
        assertEquals(OperationProgress(ProgressStage.Uploading, "Example", 50, 100), controller.progress)
        advanceUntilIdle()
        assertEquals(1, applies)
        assertEquals(ProfileScreenState.Done(true), controller.state.value)
    }

    @Test
    fun cancelledPlanningCannotReopenAndFailuresDoNotApply() = runTest {
        val service = object : ProfileFake() {
            override suspend fun add(game: GameId, reference: PackageReference): ProfileResult<ProfileEditPlan> {
                withContext(NonCancellable) { delay(100.milliseconds) }
                return ProfileResult.Success(sampleEdit)
            }

            override suspend fun planSync(game: GameId, directory: String?, target: String?, progress: ProgressSink) =
                ProfileResult.Failed(ProfileFailure.ModifiedInstallation)
        }
        val controller = ProfileController(profileGame, service, this)
        controller.add(profilePackage.id)
        runCurrent()
        controller.dismiss()
        advanceUntilIdle()
        assertEquals(ProfileScreenState.Closed, controller.state.value)
        controller.sync()
        runCurrent()
        controller.confirm()
        assertEquals(ProfileScreenState.Failed(ProfileFailure.ModifiedInstallation), controller.state.value)
    }
}
