package knitty.core.model

/** Machine-local setup, separate from portable profiles and lockfiles. */
data class SteamLibraryPlan(val directory: String)

sealed interface SetupFailure {
    data object InvalidLibrary : SetupFailure
    data object InvalidSettings : SetupFailure
    data object FilesystemFailure : SetupFailure
}

sealed interface SetupPlanOutcome {
    data class Planned(val plan: SteamLibraryPlan) : SetupPlanOutcome
    data class Failed(val failure: SetupFailure) : SetupPlanOutcome
}

sealed interface SaveSetupOutcome {
    data object Saved : SaveSetupOutcome
    data class Failed(val failure: SetupFailure) : SaveSetupOutcome
}

sealed interface SteamLibraryOutcome {
    data class Loaded(val directory: String?) : SteamLibraryOutcome
    data class Failed(val failure: SetupFailure) : SteamLibraryOutcome
}
