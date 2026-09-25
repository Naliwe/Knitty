package knitty.core.ports

import knitty.core.model.SaveSetupOutcome
import knitty.core.model.SteamLibraryOutcome

interface SteamLibrarySettings {
    suspend fun read(): SteamLibraryOutcome
    suspend fun save(directory: String): SaveSetupOutcome
}
