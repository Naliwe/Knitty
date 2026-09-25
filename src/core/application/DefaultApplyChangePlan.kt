package knitty.core.application

import knitty.core.model.*
import knitty.core.ports.DownloadArtifact
import knitty.core.ports.GameDeployment

class DefaultApplyChangePlan(
    private val downloadArtifact: DownloadArtifact,
    private val deployment: GameDeployment,
    private val providerId: String,
    private val games: Map<GameId, ProviderGameId>,
) : ApplyChangePlan {
    override suspend fun apply(plan: ChangePlan, installation: GameInstallation): ApplyOutcome {
        val providerGame = games[plan.game]
        val change = plan.changes.singleOrNull() as? Change.Install
        val valid = providerGame != null && installation.game == plan.game && change != null &&
            change.dependencies.isEmpty() && change.id.provider == providerId && change.id.value.isNotBlank() &&
            change.artifact.version.artifactId.value.isNotBlank() && change.artifact.sizeBytes > 0
        if (!valid) return ApplyOutcome.Failed(ApplyFailure.InvalidPlan)

        return deployment.install(installation, change) { sink ->
            downloadArtifact.download(providerGame, change, sink)
        }
    }
}
