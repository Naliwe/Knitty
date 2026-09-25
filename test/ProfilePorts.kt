package knitty

import knitty.core.application.ManageProfile
import knitty.core.model.*

internal abstract class ProfileFake : ManageProfile {
    override fun supports(game: GameId): Boolean = true

    override suspend fun add(game: GameId, reference: PackageReference): ProfileResult<ProfileEditPlan> =
        error("Unexpected add")

    override suspend fun remove(game: GameId, id: PackageId): ProfileResult<ProfileEditPlan> =
        error("Unexpected remove")

    override suspend fun lock(game: GameId): ProfileResult<ProfileEditPlan> = error("Unexpected lock")
    override suspend fun planUpdate(game: GameId): ProfileResult<ProfileEditPlan> = error("Unexpected update plan")
    override suspend fun save(plan: ProfileEditPlan): ProfileResult<Unit> = error("Unexpected save")
    override suspend fun planSync(
        game: GameId,
        directory: String?,
        target: String?,
        progress: ProgressSink,
    ): ProfileResult<SyncPlan> =
        error("Unexpected sync plan")

    override suspend fun apply(plan: SyncPlan, progress: ProgressSink): ProfileResult<Unit> = error("Unexpected apply")
}

internal val profileGame = GameId("core-keeper")
internal val profilePackage = LockedPackage(
    id = PackageId("modio", "1"),
    name = "Example",
    artifact = PackageArtifact(
        version = PackageVersion(ArtifactId("10"), "1.0"),
        filename = "mod.zip",
        sizeBytes = 3,
        checksum = null,
    ),
)
internal val sampleProfile = Profile("friends", profileGame, listOf(profilePackage.id))
internal val sampleLock = ProfileLock(sampleProfile.id, profileGame, listOf(profilePackage))
internal val sampleEdit = ProfileEditPlan(ProfileSnapshot(null, null, "before"), sampleProfile, sampleLock)
internal val updatedPackage = profilePackage.copy(
    artifact = profilePackage.artifact.copy(version = PackageVersion(ArtifactId("11"), "2.0")),
)
internal val sampleUpdate = ProfileEditPlan(
    ProfileSnapshot(sampleProfile, sampleLock, "before"),
    sampleProfile,
    sampleLock.copy(packages = listOf(updatedPackage)),
)
internal val sampleSync = SyncPlan(
    source = ProfileSnapshot(sampleProfile, sampleLock, "saved"),
    installation = GameInstallation(profileGame, "/game", "/game/Mods"),
    before = InstallationSnapshot(emptyList(), emptySet(), listOf("manual"), "before"),
    install = listOf(profilePackage),
    remove = emptyList(),
    keep = emptyList(),
    untouched = listOf("manual"),
)

internal val sampleDependency = profilePackage.copy(id = PackageId("modio", "2"), name = "Shared Library")
internal val sampleDependencyEdit = sampleEdit.copy(
    lock = sampleLock.copy(
        packages = listOf(
            sampleDependency,
            profilePackage.copy(dependencies = listOf(sampleDependency.id)),
        ),
    ),
)
