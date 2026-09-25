package knitty.core.model

fun planProfileSync(
    source: ProfileSnapshot,
    installation: GameInstallation,
    before: InstallationSnapshot,
): ProfileResult<SyncPlan> {
    val profile = source.profile
        ?: return ProfileResult.Failed(ProfileFailure.MissingProfile)
    val lock = source.lock
        ?: return ProfileResult.Failed(ProfileFailure.LockOutOfDate)
    if (profile.game != installation.game) return ProfileResult.Failed(ProfileFailure.WrongGame)
    if (!lock.isCompleteFor(profile)) return ProfileResult.Failed(ProfileFailure.LockOutOfDate)

    val current = before.packages.associateBy { it.id }
    val wanted = lock.packages.associateBy { it.id }
    val install = lock.packages.filter { !it.matches(current[it.id]) }
    if (install.any { it.id in current && it.id !in before.managed }) {
        return ProfileResult.Failed(ProfileFailure.Deployment(ApplyFailure.DeploymentConflict))
    }

    val replacedIds = install.map { it.id }.toSet()
    val remove = before.packages.filter {
        it.id in before.managed && (it.id !in wanted || it.id in replacedIds)
    }
    val keep = before.packages.filter { installed -> wanted[installed.id]?.matches(installed) == true }
    val standalone = before.packages.filter { it.id !in wanted && it.id !in before.managed }

    return ProfileResult.Success(
        SyncPlan(
            source = source,
            installation = installation,
            before = before,
            install = install,
            remove = remove,
            keep = keep,
            untouched = before.otherMods + standalone.map { it.name },
            adopt = keep.filter { it.id !in before.managed },
        ),
    )
}

private fun LockedPackage.matches(installed: InstalledPackage?): Boolean =
    installed?.artifactId == artifact.version.artifactId &&
        (artifact.checksum == null || artifact.checksum == installed.archiveChecksum)
