package knitty.filesystem

import knitty.core.model.ServerFailure
import knitty.core.model.ServerTarget
import kotlinx.coroutines.*
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.sftp.*
import net.schmizz.sshj.transport.verification.FingerprintVerifier
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import net.schmizz.sshj.userauth.UserAuthException
import java.io.IOException
import java.nio.file.Path
import java.security.PublicKey
import kotlin.io.path.inputStream
import kotlin.io.path.outputStream

class SftpFiles(private val environment: (String) -> String? = System::getenv) : OpenRemoteFiles {
    override suspend fun open(target: ServerTarget): RemoteFiles = withContext(Dispatchers.IO) {
        val prefix = "KNITTY_${target.name.uppercase()}_SFTP_"
        val key = environment("${prefix}KEY_FILE")?.takeIf { it.isNotBlank() }
        val password = environment("${prefix}PASSWORD")?.takeIf { it.isNotBlank() }
        if (key == null && password == null) throw RemoteFileFailure(ServerFailure.MissingCredentials)

        val ssh = SSHClient()
        var hostKeyMismatch = false
        try {
            ssh.connectTimeout = 10_000
            ssh.timeout = 30_000
            val verifier = FingerprintVerifier.getInstance(target.sftp.hostKey)
            ssh.addHostKeyVerifier(
                object : HostKeyVerifier {
                    override fun verify(host: String, port: Int, publicKey: PublicKey): Boolean =
                        verifier.verify(host, port, publicKey).also { hostKeyMismatch = !it }

                    override fun findExistingAlgorithms(host: String, port: Int): List<String> =
                        verifier.findExistingAlgorithms(host, port)
                },
            )
            ssh.connect(target.sftp.host, target.sftp.port)
            if (key != null) {
                val passphrase = environment("${prefix}KEY_PASSPHRASE")
                val provider = if (passphrase == null) ssh.loadKeys(key) else ssh.loadKeys(key, passphrase)
                ssh.authPublickey(target.sftp.username, provider)
            } else {
                ssh.authPassword(target.sftp.username, password)
            }
            SftpSession(ssh, ssh.newSFTPClient())
        } catch (failure: Exception) {
            try {
                ssh.close()
            } catch (_: IOException) {
                // Preserve the connection/authentication failure.
            }
            when {
                failure is CancellationException -> throw failure
                hostKeyMismatch -> throw RemoteFileFailure(ServerFailure.HostKeyMismatch)
                failure is UserAuthException -> throw RemoteFileFailure(ServerFailure.AuthenticationFailed)
                failure is IOException -> throw RemoteFileFailure(ServerFailure.ConnectionFailed)
                else -> throw failure
            }
        }
    }
}

private class SftpSession(private val ssh: SSHClient, private val sftp: SFTPClient) : RemoteFiles {
    override suspend fun stat(path: String): RemoteFile? = io {
        try {
            entry(path.substringAfterLast('/'), sftp.lstat(path))
        } catch (failure: SFTPException) {
            if (failure.statusCode == Response.StatusCode.NO_SUCH_FILE) null else throw failure
        }
    }

    override suspend fun list(path: String): List<RemoteFile> = io {
        sftp.ls(path).filter { it.name != "." && it.name != ".." }.map { entry(it.name, it.attributes) }
    }

    override suspend fun download(path: String, destination: Path, maximumBytes: Long, progress: (Long) -> Unit) = io {
        sftp.open(path).use { remote ->
            destination.outputStream().buffered().use { output ->
                val buffer = ByteArray(64 * 1024)
                var offset = 0L
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = remote.read(offset, buffer, 0, buffer.size)
                    if (count < 0) break
                    if (count == 0) throw RemoteFileFailure(ServerFailure.ConnectionFailed)
                    offset += count
                    if (offset > maximumBytes) throw RemoteFileFailure(ServerFailure.TransferLimitExceeded)
                    output.write(buffer, 0, count)
                    progress(offset)
                }
            }
        }
    }

    override suspend fun upload(source: Path, destination: String, progress: (Long) -> Unit) = io {
        sftp.open(destination, setOf(OpenMode.WRITE, OpenMode.CREAT, OpenMode.EXCL)).use { remote ->
            source.inputStream().buffered().use { input ->
                val buffer = ByteArray(64 * 1024)
                var offset = 0L
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    remote.write(offset, buffer, 0, count)
                    offset += count
                    progress(offset)
                }
            }
        }
    }

    override suspend fun mkdir(path: String): Unit = io { sftp.mkdir(path) }
    override suspend fun rename(source: String, destination: String): Unit = io { sftp.rename(source, destination) }
    override suspend fun removeFile(path: String): Unit = io { sftp.rm(path) }
    override suspend fun removeDirectory(path: String): Unit = io { sftp.rmdir(path) }
    override suspend fun chmod(path: String, permissions: Int): Unit = io { sftp.chmod(path, permissions) }

    override fun close() {
        ssh.use { sftp.close() }
    }

    private fun entry(name: String, attributes: FileAttributes): RemoteFile = RemoteFile(
        name,
        when (attributes.type) {
            FileMode.Type.REGULAR -> RemoteFileType.File
            FileMode.Type.DIRECTORY -> RemoteFileType.Directory
            else -> RemoteFileType.Unsupported
        },
        attributes.size,
        attributes.mode.mask and 511,
    )

    private suspend fun <T> io(action: suspend () -> T): T = withContext(Dispatchers.IO) {
        try {
            action()
        } catch (failure: SFTPException) {
            if (failure.statusCode == Response.StatusCode.PERMISSION_DENIED) {
                throw RemoteFileFailure(ServerFailure.PermissionDenied)
            }
            throw failure
        }
    }
}
