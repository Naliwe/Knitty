package knitty.core.application

import knitty.core.model.*

interface ManageProfile {
    fun supports(game: GameId): Boolean
    suspend fun add(game: GameId, reference: PackageReference): ProfileResult<ProfileEditPlan>
    suspend fun remove(game: GameId, id: PackageId): ProfileResult<ProfileEditPlan>
    suspend fun lock(game: GameId): ProfileResult<ProfileEditPlan>
    suspend fun planUpdate(game: GameId): ProfileResult<ProfileEditPlan>
    suspend fun save(plan: ProfileEditPlan): ProfileResult<Unit>
    suspend fun planSync(
        game: GameId,
        directory: String? = null,
        target: String? = null,
        progress: ProgressSink = ProgressSink.None,
    ): ProfileResult<SyncPlan>

    suspend fun apply(plan: SyncPlan, progress: ProgressSink = ProgressSink.None): ProfileResult<Unit>
}
