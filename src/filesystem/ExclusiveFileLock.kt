package knitty.filesystem

import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE

internal inline fun <T> withExclusiveFileLock(
    path: Path,
    onBusy: () -> Nothing,
    action: () -> T,
): T = FileChannel.open(path, CREATE, WRITE, NOFOLLOW_LINKS).use { channel ->
    val lock = try {
        channel.tryLock()
    } catch (_: OverlappingFileLockException) {
        null
    }
        ?: onBusy()

    lock.use { action() }
}
