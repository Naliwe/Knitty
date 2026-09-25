package knitty.games.corekeeper

import knitty.core.model.PackageId
import knitty.filesystem.sha256
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.deleteIfExists
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.io.path.isRegularFile

@Serializable
internal data class OwnershipReceipt(
    val schemaVersion: Int = 1,
    val provider: String,
    val packageId: String,
    val artifactId: String,
    val version: String?,
    val archiveMd5: String?,
    val manifestGuid: String,
    val files: Map<String, String>,
)

internal fun installationFolder(id: PackageId): String {
    val identity = "${id.provider}:${id.value}".encodeToByteArray()
    return "knitty-${MessageDigest.getInstance("SHA-256").digest(identity).toHexString()}"
}

internal suspend fun hashes(root: Path): Map<String, String> = withContext(Dispatchers.IO) {
    val result = sortedMapOf<String, String>()
    Files.walk(root).use { paths ->
        for (path in paths.filter { it.isRegularFile(NOFOLLOW_LINKS) }.toList()) {
            result[root.relativize(path).invariantSeparatorsPathString] = sha256(path)
        }
    }
    result
}

internal fun deleteStaging(root: Path) {
    Files.walk(root).use { paths ->
        paths.sorted(Comparator.reverseOrder()).forEach { it.deleteIfExists() }
    }
}
