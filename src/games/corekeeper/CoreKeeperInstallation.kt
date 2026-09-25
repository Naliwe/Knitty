package knitty.games.corekeeper

import knitty.core.model.*
import knitty.core.ports.LocateGameInstallation
import knitty.core.ports.SteamLibrarySettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.file.InvalidPathException
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import kotlin.io.path.*

class CoreKeeperInstallation(
    private val configuredDirectory: String? = null,
    private val home: Path = Path(System.getProperty("user.home")),
    private val settings: SteamLibrarySettings? = null,
) : LocateGameInstallation {
    override suspend fun locate(game: GameId, directory: String?): InstallationOutcome = withContext(Dispatchers.IO) {
        if (game != GameId("core-keeper")) {
            return@withContext InstallationOutcome.Failed(ApplyFailure.InvalidPlan)
        }
        try {
            val explicit = directory ?: configuredDirectory
            val saved = if (explicit == null) settings?.read() else null
            if (saved is SteamLibraryOutcome.Failed) {
                val failure = if (saved.failure == SetupFailure.FilesystemFailure) {
                    ApplyFailure.FilesystemFailure
                } else {
                    ApplyFailure.InvalidSettings
                }
                return@withContext InstallationOutcome.Failed(failure)
            }
            val library = (saved as? SteamLibraryOutcome.Loaded)?.directory
            val candidates = when {
                explicit != null -> listOf(Path(explicit))
                library != null -> listOf(Path(library).resolve("steamapps/common/Core Keeper"))
                else -> listOf(
                    home.resolve(".local/share/Steam/steamapps/common/Core Keeper"),
                    home.resolve(".steam/steam/steamapps/common/Core Keeper"),
                )
            }
            val installations = candidates.mapNotNull { root ->
                if (!root.exists()) return@mapNotNull null
                val canonical = root.toRealPath()
                val executable = listOf("CoreKeeper", "CoreKeeperServer").singleOrNull { name ->
                    listOf(name, "$name.x86_64", "$name.exe")
                        .any { canonical.resolve(it).isRegularFile(NOFOLLOW_LINKS) }
                } ?: return@mapNotNull null
                val assets = canonical.resolve("${executable}_Data/StreamingAssets")
                if (!assets.isDirectory(NOFOLLOW_LINKS) || !safeAncestors(assets)) {
                    return@mapNotNull null
                }
                val mods = assets.resolve("Mods")
                if (mods.exists(NOFOLLOW_LINKS) && !mods.isDirectory(NOFOLLOW_LINKS)) return@mapNotNull null
                GameInstallation(game, canonical.toString(), mods.toString())
            }.distinct()
            when (installations.size) {
                0 -> InstallationOutcome.Failed(ApplyFailure.InstallationNotFound)
                1 -> InstallationOutcome.Found(installations.single())
                else -> InstallationOutcome.Failed(ApplyFailure.AmbiguousInstallation)
            }
        } catch (_: IOException) {
            InstallationOutcome.Failed(ApplyFailure.FilesystemFailure)
        } catch (_: InvalidPathException) {
            InstallationOutcome.Failed(ApplyFailure.InstallationNotFound)
        }
    }
}

internal fun safeAncestors(path: Path): Boolean = generateSequence(path.toAbsolutePath()) { it.parent }
    .none { it.isSymbolicLink() }
