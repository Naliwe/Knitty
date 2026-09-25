package knitty.settings

import knitty.core.model.SetupFailure
import knitty.core.model.SetupPlanOutcome
import knitty.core.model.SteamLibraryPlan
import knitty.core.ports.ValidateSteamLibrary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.file.InvalidPathException
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.isDirectory

class SteamLibraryDirectory(
    private val home: Path = Path(System.getProperty("user.home")),
) : ValidateSteamLibrary {
    override suspend fun validate(directory: String): SetupPlanOutcome = withContext(Dispatchers.IO) {
        try {
            if (directory.isBlank()) return@withContext SetupPlanOutcome.Failed(SetupFailure.InvalidLibrary)
            val expanded = when {
                directory == "~" -> home
                directory.startsWith("~/") -> home.resolve(directory.removePrefix("~/"))
                else -> Path(directory)
            }
            if (!expanded.resolve("steamapps").isDirectory()) {
                return@withContext SetupPlanOutcome.Failed(SetupFailure.InvalidLibrary)
            }
            SetupPlanOutcome.Planned(SteamLibraryPlan(expanded.toRealPath().toString()))
        } catch (_: InvalidPathException) {
            SetupPlanOutcome.Failed(SetupFailure.InvalidLibrary)
        } catch (_: IOException) {
            SetupPlanOutcome.Failed(SetupFailure.FilesystemFailure)
        }
    }
}
