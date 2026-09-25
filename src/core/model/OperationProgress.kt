package knitty.core.model

enum class ProgressStage {
    Resolving, Checking, Downloading, Preparing, Uploading, Verifying,
    StoppingServer, Committing, StartingServer, SavingProfile,
}

enum class ProgressUnit { Bytes, Items }

data class OperationProgress(
    val stage: ProgressStage,
    val item: String? = null,
    val completed: Long = 0,
    val total: Long? = null,
    val unit: ProgressUnit = ProgressUnit.Bytes,
)

fun interface ProgressSink {
    fun report(progress: OperationProgress)

    companion object {
        val None = ProgressSink { }
    }
}
