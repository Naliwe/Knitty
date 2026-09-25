package knitty.tui

import knitty.core.application.ManageProfile
import knitty.core.model.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface ProfileScreenState {
    data object Closed : ProfileScreenState
    data object Planning : ProfileScreenState
    data class Edit(val plan: ProfileEditPlan) : ProfileScreenState
    data class Outdated(val plan: ProfileEditPlan) : ProfileScreenState
    data class Sync(val plan: SyncPlan) : ProfileScreenState
    data object Applying : ProfileScreenState
    data class Done(val synchronized: Boolean) : ProfileScreenState
    data class Failed(val failure: ProfileFailure) : ProfileScreenState
}

class ProfileController(
    private val game: GameId,
    private val profiles: ManageProfile,
    private val scope: CoroutineScope,
    private val target: String? = null,
) {
    private val mutableState = MutableStateFlow<ProfileScreenState>(ProfileScreenState.Closed)
    val state = mutableState.asStateFlow()

    private var job: Job? = null
    private var operationProgress = MutableStateFlow(OperationProgress(ProgressStage.Resolving))
    val progress: OperationProgress get() = operationProgress.value

    private fun beginProgress(stage: ProgressStage): ProgressSink {
        val updates = MutableStateFlow(OperationProgress(stage))
        operationProgress = updates

        return ProgressSink { updates.value = it }
    }

    fun add(id: PackageId) = plan { profiles.add(game, PackageReference(id.provider, id.value)).mapEdit() }
    fun remove(id: PackageId) = plan { profiles.remove(game, id).mapEdit() }
    fun lock() = plan { profiles.lock(game).mapEdit() }
    fun outdated() = plan { updateState(reviewOnly = true) }

    fun update() {
        val report = state.value
        if (report is ProfileScreenState.Outdated) {
            if (report.plan.hasChanges) mutableState.value = ProfileScreenState.Edit(report.plan)
        } else {
            plan { updateState(reviewOnly = false) }
        }
    }

    private suspend fun updateState(reviewOnly: Boolean): ProfileScreenState =
        when (val result = profiles.planUpdate(game)) {
            is ProfileResult.Failed -> ProfileScreenState.Failed(result.failure)
            is ProfileResult.Success -> {
                if (reviewOnly || !result.value.hasChanges) {
                    ProfileScreenState.Outdated(result.value)
                } else {
                    ProfileScreenState.Edit(result.value)
                }
            }
        }

    fun sync() = plan { progress ->
        when (val result = profiles.planSync(game, target = target, progress = progress)) {
            is ProfileResult.Success -> ProfileScreenState.Sync(result.value)
            is ProfileResult.Failed -> ProfileScreenState.Failed(result.failure)
        }
    }

    fun confirm() {
        val ready = state.value
        if (ready !is ProfileScreenState.Edit && ready !is ProfileScreenState.Sync) return

        val stage = if (ready is ProfileScreenState.Edit) ProgressStage.SavingProfile else ProgressStage.Checking
        val progress = beginProgress(stage)
        mutableState.value = ProfileScreenState.Applying

        job = scope.launch {
            val result = when (ready) {
                is ProfileScreenState.Edit -> profiles.save(ready.plan)
                is ProfileScreenState.Sync -> profiles.apply(ready.plan, progress)
            }

            mutableState.value = when (result) {
                is ProfileResult.Success -> ProfileScreenState.Done(ready is ProfileScreenState.Sync)
                is ProfileResult.Failed -> ProfileScreenState.Failed(result.failure)
            }
        }
    }

    fun dismiss() {
        if (state.value == ProfileScreenState.Applying) return

        job?.cancel()
        mutableState.value = ProfileScreenState.Closed
    }

    suspend fun close() {
        if (state.value == ProfileScreenState.Applying) job?.join() else dismiss()
    }

    private fun plan(action: suspend (ProgressSink) -> ProfileScreenState) {
        if (state.value == ProfileScreenState.Applying) return

        job?.cancel()
        val progress = beginProgress(ProgressStage.Resolving)
        mutableState.value = ProfileScreenState.Planning

        job = scope.launch {
            val result = action(progress)
            ensureActive()
            mutableState.value = result
        }
    }
}

private fun ProfileResult<ProfileEditPlan>.mapEdit(): ProfileScreenState = when (this) {
    is ProfileResult.Success -> ProfileScreenState.Edit(value)
    is ProfileResult.Failed -> ProfileScreenState.Failed(failure)
}
