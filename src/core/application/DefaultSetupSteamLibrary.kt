package knitty.core.application

import knitty.core.model.SaveSetupOutcome
import knitty.core.model.SetupPlanOutcome
import knitty.core.model.SteamLibraryPlan
import knitty.core.ports.SteamLibrarySettings
import knitty.core.ports.ValidateSteamLibrary

class DefaultSetupSteamLibrary(
    private val validateLibrary: ValidateSteamLibrary,
    private val settings: SteamLibrarySettings,
) : SetupSteamLibrary {
    override suspend fun plan(directory: String): SetupPlanOutcome = validateLibrary.validate(directory)

    override suspend fun save(plan: SteamLibraryPlan): SaveSetupOutcome = when (val checked = plan(plan.directory)) {
        is SetupPlanOutcome.Failed -> SaveSetupOutcome.Failed(checked.failure)
        is SetupPlanOutcome.Planned -> settings.save(checked.plan.directory)
    }
}
