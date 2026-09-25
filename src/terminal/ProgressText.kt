package knitty.terminal

import knitty.core.model.OperationProgress
import knitty.core.model.ProgressStage
import knitty.core.model.ProgressUnit

fun progressTitle(stage: ProgressStage): String = when (stage) {
    ProgressStage.Resolving -> "Resolving packages"
    ProgressStage.Checking -> "Checking installation"
    ProgressStage.Downloading -> "Downloading packages"
    ProgressStage.Preparing -> "Preparing mods"
    ProgressStage.Uploading -> "Uploading mods"
    ProgressStage.Verifying -> "Verifying files"
    ProgressStage.StoppingServer -> "Stopping server"
    ProgressStage.Committing -> "Installing mods"
    ProgressStage.StartingServer -> "Starting server"
    ProgressStage.SavingProfile -> "Saving profile"
}

fun progressFraction(progress: OperationProgress): Double? = progress.total?.let { total ->
    if (total <= 0) 1.0 else (progress.completed.toDouble() / total).coerceIn(0.0, 1.0)
}

fun progressAmount(progress: OperationProgress): String {
    val total = progress.total
        ?: return "Working…"
    val completed = progress.completed.coerceIn(0, total.coerceAtLeast(0))
    val amount = when (progress.unit) {
        ProgressUnit.Bytes -> "${displaySize(completed)} / ${displaySize(total)}"
        ProgressUnit.Items -> "$completed / $total mods"
    }
    return "${(progressFraction(progress)!! * 100).toInt()}% · $amount"
}

fun progressBar(progress: OperationProgress, width: Int, tick: Long = 0): String {
    val columns = width.coerceAtLeast(1)
    val fraction = progressFraction(progress)
    if (fraction != null) {
        val filled = (fraction * columns).toInt()
        return "#".repeat(filled) + "-".repeat(columns - filled)
    }
    val position = (tick % columns).toInt()
    return "-".repeat(position) + ">" + "-".repeat(columns - position - 1)
}
