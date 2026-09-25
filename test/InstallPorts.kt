package knitty

import knitty.core.application.SetupSteamLibrary
import knitty.core.model.SaveSetupOutcome
import knitty.core.model.SetupPlanOutcome
import knitty.core.model.SteamLibraryPlan

internal val unusedSetup = object : SetupSteamLibrary {
    override suspend fun plan(directory: String): SetupPlanOutcome = error("Must not plan setup")
    override suspend fun save(plan: SteamLibraryPlan): SaveSetupOutcome = error("Must not save setup")
}
