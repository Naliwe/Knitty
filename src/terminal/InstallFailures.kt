package knitty.terminal

import knitty.core.model.ApplyFailure
import knitty.core.model.PlanInstallFailure

internal fun planFailureMessage(failure: PlanInstallFailure): String = when (failure) {
    is PlanInstallFailure.UnsupportedGame -> "Unsupported game '${plainText(failure.game.value)}'. Try core-keeper."
    is PlanInstallFailure.UnsupportedProvider -> "Unsupported provider '${plainText(failure.provider)}'. Try modio."
    PlanInstallFailure.InvalidPackageReference -> "Invalid package reference. Use a mod.io numeric ID or URL slug."
    PlanInstallFailure.NoPublishedFile -> "This mod has no published file to install."
    PlanInstallFailure.ArtifactUnavailable -> "The published file is unavailable or flagged by the provider."
    is PlanInstallFailure.DependencyCycle -> "Dependency cycle: " +
        failure.packages.joinToString(" -> ") { plainText("${it.provider}:${it.value}") }

    PlanInstallFailure.DependencyLimitExceeded -> "Dependency graph exceeds 500 mods. Check the mod's declarations."
    is PlanInstallFailure.DependencyUnavailable -> {
        val requiredBy = plainText(failure.requiredBy.value)
        val dependency = plainText(failure.dependency.value)
        "$requiredBy requires $dependency: ${planFailureMessage(failure.cause)}"
    }

    is PlanInstallFailure.Provider -> providerFailureMessage(failure.failure)
}

internal fun applyFailureMessage(failure: ApplyFailure): String = when (failure) {
    ApplyFailure.InvalidSettings -> "Knitty local settings are invalid. Check knitty/settings.json in your config directory."
    ApplyFailure.InvalidPlan -> "This plan cannot be applied. Review a fresh profile sync plan."
    ApplyFailure.InstallationNotFound -> "Core Keeper installation not found. Run knitty setup to choose a Steam library, or pass --installation with the game directory."
    ApplyFailure.AmbiguousInstallation -> "Multiple installations found. Set KNITTY_CORE_KEEPER_DIR and restart, or use sync --installation."
    ApplyFailure.DeploymentConflict -> "An existing mod or unsafe path conflicts with this install. No existing mods were replaced."
    ApplyFailure.InstallationBusy -> "Another Knitty install is running for this game. Try again when it finishes."
    ApplyFailure.DownloadFailed -> "The archive download failed. Check your connection and retry."
    ApplyFailure.ArtifactChanged -> "The archive differs from the plan. Make a fresh plan before retrying."
    ApplyFailure.ChecksumMismatch -> "The archive checksum does not match. Nothing was installed; retry the download."
    ApplyFailure.InvalidArchive -> "This is not a supported native Core Keeper mod ZIP, or it exceeds the install limits."
    ApplyFailure.DependenciesChanged -> "Dependency declarations changed. Review knitty update before syncing again."
    is ApplyFailure.MissingDependency ->
        "${plainText(failure.packageName)} requires ${plainText(failure.dependencyName)}. Add it to the profile and sync again."

    ApplyFailure.FilesystemFailure -> "Could not stage or commit the install. Check disk space and write permissions."
    ApplyFailure.AtomicCommitUnavailable -> "This filesystem cannot commit the mod atomically. Use an installation on a supported filesystem."
    is ApplyFailure.Provider -> providerFailureMessage(failure.failure)
}
