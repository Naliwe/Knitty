package knitty.filesystem

import knitty.core.model.ServerFailure
import knitty.core.model.ServerTarget
import java.io.Closeable
import java.io.IOException
import java.nio.file.Path

enum class RemoteFileType { File, Directory, Unsupported }

data class RemoteFile(val name: String, val type: RemoteFileType, val size: Long, val permissions: Int = 420)

fun interface OpenRemoteFiles {
    suspend fun open(target: ServerTarget): RemoteFiles
}

interface RemoteFiles : Closeable {
    suspend fun stat(path: String): RemoteFile?
    suspend fun list(path: String): List<RemoteFile>
    suspend fun download(path: String, destination: Path, maximumBytes: Long, progress: (Long) -> Unit = {})
    suspend fun upload(source: Path, destination: String, progress: (Long) -> Unit = {})
    suspend fun mkdir(path: String)
    suspend fun rename(source: String, destination: String)
    suspend fun removeFile(path: String)
    suspend fun removeDirectory(path: String)
    suspend fun chmod(path: String, permissions: Int)
}

class RemoteFileFailure(val failure: ServerFailure) : IOException()
