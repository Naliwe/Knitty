package knitty.core.ports

import knitty.core.model.SetupPlanOutcome

fun interface ValidateSteamLibrary {
    suspend fun validate(directory: String): SetupPlanOutcome
}
