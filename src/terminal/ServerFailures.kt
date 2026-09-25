package knitty.terminal

import knitty.core.model.ServerFailure

fun serverFailureMessage(failure: ServerFailure): String = when (failure) {
    ServerFailure.UnknownTarget -> "No server with that name in knitty.yaml. Check servers and --target."
    ServerFailure.InvalidTarget -> "Invalid server target or unsafe remote path. Check the profile and server layout."
    ServerFailure.MissingCredentials -> "Server credentials are missing. Set the target's Pelican token and SFTP key or password; see docs/server-sync-slice.md."
    ServerFailure.AuthenticationFailed -> "Server authentication failed. Check the target's Pelican token and SFTP credentials."
    ServerFailure.HostKeyMismatch -> "The SFTP host key differs from the profile. Verify the Wings fingerprint before changing hostKey."
    ServerFailure.PermissionDenied -> "Server access was denied. Check Pelican API permissions and SFTP file permissions."
    ServerFailure.ConnectionFailed -> "Could not complete the remote operation. Check connectivity and inspect server state before retrying."
    ServerFailure.InvalidResponse -> "Unexpected Pelican response. Check the panel URL, server UUID, API access, and AUTO_UPDATE variable."
    ServerFailure.PowerTimeout -> "Pelican did not reach the requested power state in time. Check the panel; no forced kill was sent."
    ServerFailure.ServerBusy -> "The server is starting, stopping, or busy. Wait and review a new plan."
    ServerFailure.TransferLimitExceeded -> "Remote mods exceed the transfer limits (2 GiB, 20,000 entries, or 100 directory levels)."
    ServerFailure.RecoveryRequired -> "A remote transaction needs attention. Keep the server stopped and preserve .knitty-server-sync; follow docs/server-sync-slice.md."
    ServerFailure.CommitRolledBack -> "Remote publication failed. The original mods were restored; review server state before retrying."
    ServerFailure.RestartFailed -> "Mods were deployed, but restarting could not be confirmed. Check Pelican before starting or retrying."
}
