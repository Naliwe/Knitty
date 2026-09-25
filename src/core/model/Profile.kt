package knitty.core.model

data class Profile(
    val id: String,
    val game: GameId,
    val packages: List<PackageId>,
    val servers: List<ServerTarget> = emptyList(),
)

data class LockedPackage(
    val id: PackageId,
    val name: String,
    val artifact: PackageArtifact,
    val dependencies: List<PackageId> = emptyList(),
) {
    fun install() = Change.Install(id, name, artifact, dependencies)
}

data class ProfileLock(
    val profileId: String,
    val game: GameId,
    val packages: List<LockedPackage>,
)

data class ProfileSnapshot(
    val profile: Profile?,
    val lock: ProfileLock?,
    val revision: String,
)

data class ProfileEditPlan(
    val before: ProfileSnapshot,
    val profile: Profile,
    val lock: ProfileLock,
) {
    val additions: List<LockedPackage>
        get() = lock.packages.filter { item -> before.lock?.packages.orEmpty().none { it.id == item.id } }

    val removals: List<LockedPackage>
        get() = before.lock?.packages.orEmpty().filter { item -> lock.packages.none { it.id == item.id } }

    val hasChanges: Boolean
        get() = additions.isNotEmpty() || removals.isNotEmpty() || updates.isNotEmpty()

    val updates: List<PackageUpdate>
        get() {
            val previous = before.lock?.packages.orEmpty().associateBy { it.id }

            return lock.packages.mapNotNull { replacement ->
                val current = previous[replacement.id]
                    ?: return@mapNotNull null

                if (current.artifact == replacement.artifact && current.dependencies == replacement.dependencies) {
                    null
                } else {
                    PackageUpdate(current, replacement)
                }
            }
        }
}

data class PackageUpdate(val current: LockedPackage, val replacement: LockedPackage)

data class InstalledPackage(
    val id: PackageId,
    val artifactId: ArtifactId,
    val name: String,
    val archiveChecksum: Checksum? = null,
)

data class InstallationSnapshot(
    val packages: List<InstalledPackage>,
    val managed: Set<PackageId>,
    val otherMods: List<String>,
    val revision: String,
)

data class SyncPlan(
    val source: ProfileSnapshot,
    val installation: GameInstallation,
    val before: InstallationSnapshot,
    val install: List<LockedPackage>,
    val remove: List<InstalledPackage>,
    val keep: List<InstalledPackage>,
    val untouched: List<String>,
    val adopt: List<InstalledPackage> = emptyList(),
    val server: ServerSync? = null,
)

sealed interface ProfileFailure {
    data object MissingProfile : ProfileFailure
    data object InvalidProfile : ProfileFailure
    data object InvalidLock : ProfileFailure
    data object LockOutOfDate : ProfileFailure
    data object WrongGame : ProfileFailure
    data object PackageNotInProfile : ProfileFailure
    data object StalePlan : ProfileFailure
    data object Busy : ProfileFailure
    data object FilesystemFailure : ProfileFailure
    data object RecoveryRequired : ProfileFailure
    data object DifferentProfile : ProfileFailure
    data object ModifiedInstallation : ProfileFailure
    data class Planning(val failure: PlanInstallFailure) : ProfileFailure
    data class Deployment(val failure: ApplyFailure) : ProfileFailure
    data class Server(val failure: ServerFailure) : ProfileFailure
    data class ServerStopped(val cause: ProfileFailure) : ProfileFailure
}

sealed interface ProfileResult<out T> {
    data class Success<T>(val value: T) : ProfileResult<T>
    data class Failed(val failure: ProfileFailure) : ProfileResult<Nothing>
}

inline fun <T, R> ProfileResult<T>.map(transform: (T) -> R): ProfileResult<R> = when (this) {
    is ProfileResult.Success -> ProfileResult.Success(transform(value))
    is ProfileResult.Failed -> this
}
