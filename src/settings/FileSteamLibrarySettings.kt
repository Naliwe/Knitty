package knitty.settings

import knitty.core.model.SaveSetupOutcome
import knitty.core.model.SetupFailure
import knitty.core.model.SteamLibraryOutcome
import knitty.core.ports.SteamLibrarySettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.IOException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import kotlin.io.path.*

class FileSteamLibrarySettings(private val file: Path) : SteamLibrarySettings {
    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
    }

    override suspend fun read(): SteamLibraryOutcome = withContext(Dispatchers.IO) {
        try {
            if (!file.exists(NOFOLLOW_LINKS)) return@withContext SteamLibraryOutcome.Loaded(null)
            if (!file.isRegularFile(NOFOLLOW_LINKS) || file.fileSize() > 64 * 1024) {
                return@withContext SteamLibraryOutcome.Failed(SetupFailure.InvalidSettings)
            }
            val settings = json.decodeFromString<SettingsFile>(file.readText())
            if (settings.schemaVersion != 1 || settings.steamLibrary.isBlank() || !Path(settings.steamLibrary).isAbsolute) {
                return@withContext SteamLibraryOutcome.Failed(SetupFailure.InvalidSettings)
            }
            SteamLibraryOutcome.Loaded(settings.steamLibrary)
        } catch (_: SerializationException) {
            SteamLibraryOutcome.Failed(SetupFailure.InvalidSettings)
        } catch (_: InvalidPathException) {
            SteamLibraryOutcome.Failed(SetupFailure.InvalidSettings)
        } catch (_: IOException) {
            SteamLibraryOutcome.Failed(SetupFailure.FilesystemFailure)
        }
    }

    override suspend fun save(directory: String): SaveSetupOutcome = withContext(Dispatchers.IO) {
        val existing = read()
        if (existing is SteamLibraryOutcome.Failed) return@withContext SaveSetupOutcome.Failed(existing.failure)
        var temporary: Path? = null
        try {
            val parent = file.toAbsolutePath().parent.createDirectories()
            temporary = Files.createTempFile(parent, ".settings-", ".json")
            temporary.writeText(json.encodeToString(SettingsFile(steamLibrary = directory)) + "\n")
            Files.move(temporary, file, ATOMIC_MOVE, REPLACE_EXISTING)
            SaveSetupOutcome.Saved
        } catch (_: IOException) {
            SaveSetupOutcome.Failed(SetupFailure.FilesystemFailure)
        } finally {
            try {
                temporary?.deleteIfExists()
            } catch (_: IOException) {
                // A stale temporary settings file is never read as configuration.
            }
        }
    }
}

fun localSettingsPath(
    home: Path = Path(System.getProperty("user.home")),
    configHome: String? = System.getenv("XDG_CONFIG_HOME"),
): Path {
    val configured = configHome?.takeIf { it.isNotBlank() }?.let { Path(it) }?.takeIf { it.isAbsolute }
    return (configured ?: home.resolve(".config")).resolve("knitty/settings.json")
}

@Serializable
private data class SettingsFile(val schemaVersion: Int = 1, val steamLibrary: String)
