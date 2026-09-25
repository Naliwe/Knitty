package knitty.core.application

import knitty.core.model.SaveSetupOutcome
import knitty.core.model.SetupPlanOutcome
import knitty.core.model.SteamLibraryPlan

interface SetupSteamLibrary {
    suspend fun plan(directory: String): SetupPlanOutcome
    suspend fun save(plan: SteamLibraryPlan): SaveSetupOutcome
}
