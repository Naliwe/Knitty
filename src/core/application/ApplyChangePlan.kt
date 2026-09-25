package knitty.core.application

import knitty.core.model.ApplyOutcome
import knitty.core.model.ChangePlan
import knitty.core.model.GameInstallation

fun interface ApplyChangePlan {
    suspend fun apply(plan: ChangePlan, installation: GameInstallation): ApplyOutcome
}
