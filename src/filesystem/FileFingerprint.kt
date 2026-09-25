package knitty.filesystem

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.inputStream

internal suspend fun sha256(path: Path): String = withContext(Dispatchers.IO) {
    val digest = MessageDigest.getInstance("SHA-256")

    path.inputStream().buffered().use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            currentCoroutineContext().ensureActive()
            val size = input.read(buffer)
            if (size < 0) break

            digest.update(buffer, 0, size)
        }
    }

    digest.digest().toHexString()
}
