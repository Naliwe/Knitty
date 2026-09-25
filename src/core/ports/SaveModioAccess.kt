package knitty.core.ports

import knitty.core.model.ModioAccess
import knitty.core.model.ModioSetupOutcome

fun interface SaveModioAccess {
    suspend fun save(access: ModioAccess): ModioSetupOutcome
}
