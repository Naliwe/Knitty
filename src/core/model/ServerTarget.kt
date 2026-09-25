package knitty.core.model

data class ServerTarget(
    val name: String,
    val panelUrl: String,
    val serverId: String,
    val sftp: SftpEndpoint,
)

data class SftpEndpoint(
    val host: String,
    val port: Int,
    val username: String,
    val hostKey: String,
)

enum class ServerPowerState { Offline, Running, Starting, Stopping }

data class ServerStatus(val power: ServerPowerState, val autoUpdate: Boolean)

data class ServerSync(val target: ServerTarget, val before: ServerStatus)

data class ServerInspection(val installation: GameInstallation, val snapshot: InstallationSnapshot)

data class ServerLayout(val executable: String, val modsDirectory: String)

sealed interface ServerFailure {
    data object UnknownTarget : ServerFailure
    data object InvalidTarget : ServerFailure
    data object MissingCredentials : ServerFailure
    data object AuthenticationFailed : ServerFailure
    data object HostKeyMismatch : ServerFailure
    data object PermissionDenied : ServerFailure
    data object ConnectionFailed : ServerFailure
    data object InvalidResponse : ServerFailure
    data object PowerTimeout : ServerFailure
    data object ServerBusy : ServerFailure
    data object TransferLimitExceeded : ServerFailure
    data object RecoveryRequired : ServerFailure
    data object CommitRolledBack : ServerFailure
    data object RestartFailed : ServerFailure
}
