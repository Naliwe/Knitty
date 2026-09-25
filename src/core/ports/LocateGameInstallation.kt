package knitty.core.ports

import knitty.core.model.GameId
import knitty.core.model.InstallationOutcome

fun interface LocateGameInstallation {
    suspend fun locate(game: GameId, directory: String?): InstallationOutcome
}
