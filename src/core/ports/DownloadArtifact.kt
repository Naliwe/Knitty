package knitty.core.ports

import knitty.core.model.Change
import knitty.core.model.DownloadOutcome
import knitty.core.model.ProviderGameId

fun interface DownloadArtifact {
    suspend fun download(game: ProviderGameId, change: Change.Install, sink: ArtifactSink): DownloadOutcome
}
