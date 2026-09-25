package knitty.filesystem

import knitty.core.model.OperationProgress
import knitty.core.model.ProgressSink
import knitty.core.model.ProgressStage
import knitty.core.model.ServerFailure
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.*

internal data class RemoteTree(val permissions: Map<String, Int>, val revision: String)

internal suspend fun RemoteFiles.downloadTree(
    remote: String,
    local: Path,
    progress: ProgressSink = ProgressSink.None,
    stage: ProgressStage = ProgressStage.Checking,
): RemoteTree = withContext(Dispatchers.IO) {
    val permissions = sortedMapOf<String, Int>()
    val digest = MessageDigest.getInstance("SHA-256")
    var total = 0L
    var count = 0

    suspend fun visit(path: String, destination: Path, relative: String, depth: Int) {
        currentCoroutineContext().ensureActive()
        if (++count > 20_000 || depth > 100) throw RemoteFileFailure(ServerFailure.TransferLimitExceeded)
        val entry = stat(path)
            ?: throw RemoteFileFailure(ServerFailure.ConnectionFailed)
        permissions[relative] = entry.permissions
        digest.update("$relative\u0000${entry.type}\u0000${entry.permissions}\u0000".encodeToByteArray())
        when (entry.type) {
            RemoteFileType.Directory -> {
                destination.createDirectories()
                val names = mutableSetOf<String>()
                for ((name) in list(path).sortedBy { it.name }) {
                    validateRemoteName(name)
                    if (!names.add(name.lowercase())) throw RemoteFileFailure(ServerFailure.InvalidTarget)
                    visit("$path/$name", destination.resolve(name), "$relative/$name", depth + 1)
                }
            }

            RemoteFileType.File -> {
                digest.update("${entry.size}\u0000".encodeToByteArray())
                if (entry.size < 0 || entry.size > 2_147_483_648L - total) {
                    throw RemoteFileFailure(ServerFailure.TransferLimitExceeded)
                }
                total += entry.size
                val transfer = OperationProgress(stage, relative.removePrefix("/"), total = entry.size)
                progress.report(transfer)
                download(path, destination, entry.size) { received ->
                    progress.report(transfer.copy(completed = received))
                }
                if (destination.fileSize() != entry.size) throw RemoteFileFailure(ServerFailure.ConnectionFailed)
                digest.update(sha256(destination).encodeToByteArray())
                digest.update(0.toByte())
            }

            RemoteFileType.Unsupported -> throw RemoteFileFailure(ServerFailure.InvalidTarget)
        }
    }

    if (stat(remote) != null) visit(remote, local, "", 0)
    RemoteTree(permissions, digest.digest().toHexString())
}

internal suspend fun RemoteFiles.uploadTree(
    local: Path,
    remote: String,
    permissions: Map<String, Int>,
    progress: ProgressSink = ProgressSink.None,
): Unit = withContext(Dispatchers.IO) {
    Files.walk(local).use { paths ->
        for (path in paths.sorted().toList()) {
            currentCoroutineContext().ensureActive()
            val relative = local.relativize(path).invariantSeparatorsPathString
            val destination = if (relative.isEmpty()) remote else "$remote/$relative"
            if (path.isDirectory()) {
                mkdir(destination)
            } else {
                val transfer = OperationProgress(ProgressStage.Uploading, relative, total = path.fileSize())
                progress.report(transfer)
                upload(path, destination) { sent -> progress.report(transfer.copy(completed = sent)) }
            }
            val original = permissions[if (relative.isEmpty()) "" else "/$relative"]
            if (!path.isDirectory()) chmod(destination, original ?: 420)
        }
    }
    Files.walk(local).use { paths ->
        for (path in paths.filter { it.isDirectory() }.sorted(Comparator.reverseOrder()).toList()) {
            val relative = local.relativize(path).invariantSeparatorsPathString
            val destination = if (relative.isEmpty()) remote else "$remote/$relative"
            chmod(destination, permissions[if (relative.isEmpty()) "" else "/$relative"] ?: 493)
        }
    }
}

internal suspend fun RemoteFiles.deleteTree(path: String) {
    val entry = stat(path)
        ?: return
    if (entry.type == RemoteFileType.Directory) {
        for ((name) in list(path)) {
            validateRemoteName(name)
            deleteTree("$path/$name")
        }
        removeDirectory(path)
    } else {
        removeFile(path)
    }
}

private fun validateRemoteName(name: String) {
    val invalidCharacter = name.any { it in "\\/<>:\"|?*" || it.code < 32 }
    val reservedName = Regex("(?i)(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(\\..*)?").matches(name)
    if (name.isEmpty() || name.endsWith('.') || name.endsWith(' ') || invalidCharacter || reservedName) {
        throw RemoteFileFailure(ServerFailure.InvalidTarget)
    }
}

internal fun deleteLocalTree(path: Path) {
    if (!path.exists()) return
    Files.walk(path).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { it.deleteIfExists() } }
}
