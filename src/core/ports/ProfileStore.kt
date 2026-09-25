package knitty.core.ports

import knitty.core.model.ProfileEditPlan
import knitty.core.model.ProfileResult
import knitty.core.model.ProfileSnapshot

interface ProfileStore {
    suspend fun read(): ProfileResult<ProfileSnapshot>
    suspend fun save(plan: ProfileEditPlan): ProfileResult<Unit>
    suspend fun <T> withSnapshot(snapshot: ProfileSnapshot, action: suspend () -> ProfileResult<T>): ProfileResult<T>
}
