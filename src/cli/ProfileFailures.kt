package knitty.cli

import com.github.ajalt.clikt.core.CliktError
import knitty.core.model.ProfileFailure
import knitty.core.model.ProfileResult
import knitty.terminal.applyFailureMessage
import knitty.terminal.planFailureMessage
import knitty.terminal.serverFailureMessage

internal fun profileFailureMessage(failure: ProfileFailure): String = when (failure) {
    ProfileFailure.MissingProfile -> "No knitty.yaml found. Start with knitty add or select --profile DIRECTORY."
    ProfileFailure.InvalidProfile -> "Invalid knitty.yaml. Check schemaVersion, id, game, and unique numeric modio IDs in mods."
    ProfileFailure.InvalidLock -> "Invalid knitty.lock or mismatched profile identity. Restore the shared lockfile."
    ProfileFailure.LockOutOfDate -> "The lockfile does not match the profile. Run knitty lock for this profile, then sync."
    ProfileFailure.WrongGame -> "The requested game does not match this profile or is unsupported."
    ProfileFailure.PackageNotInProfile -> "This mod is not explicitly requested. Dependencies are kept until their parent mods are removed."
    ProfileFailure.StalePlan -> "Profile or installation changed after planning. Review a new plan."
    ProfileFailure.Busy -> "Another Knitty operation is using this profile or installation. Retry later."
    ProfileFailure.FilesystemFailure -> "Could not read, stage, or commit files. Check permissions and free space."
    ProfileFailure.RecoveryRequired -> "An interrupted transaction needs recovery. Keep the backup and follow docs/profile-slice.md before retrying."
    ProfileFailure.DifferentProfile -> "This installation is bound to another profile. Use that profile or a separate installation."
    ProfileFailure.ModifiedInstallation -> "Installed files differ from Knitty's ownership records. Preserve your changes and restore the recorded files before syncing."
    is ProfileFailure.Planning -> planFailureMessage(failure.failure)
    is ProfileFailure.Deployment -> applyFailureMessage(failure.failure)
    is ProfileFailure.Server -> serverFailureMessage(failure.failure)
    is ProfileFailure.ServerStopped -> {
        val cause = profileFailureMessage(failure.cause)
        "Knitty stopped the server and did not request a restart. Check Pelican. $cause"
    }
}

internal fun <T> ProfileResult<T>.requireValue(): T = when (this) {
    is ProfileResult.Success -> value
    is ProfileResult.Failed -> throw CliktError(profileFailureMessage(failure))
}
