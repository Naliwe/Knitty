package knitty.integration

import knitty.core.model.ServerFailure
import knitty.filesystem.RemoteFileFailure
import knitty.filesystem.RemoteFileType
import knitty.filesystem.SftpFiles
import knitty.sampleServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.io.IOException
import java.nio.file.Files
import kotlin.io.path.Path
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes
import kotlin.test.*

@EnabledIfEnvironmentVariable(named = "KNITTY_TEST_SFTP", matches = "1")
class SftpFilesLiveTest {
    @Test
    fun verifiesHostKeysAuthenticatesAndTransfersThroughARealSftpServer() = runBlocking {
        withContext(Dispatchers.IO) {
            val root = Path(System.getenv("KNITTY_TEST_SFTP_ROOT"))
            val target = sampleServer.copy(
                sftp = sampleServer.sftp.copy(
                    host = "127.0.0.1",
                    port = System.getenv("KNITTY_TEST_SFTP_PORT").toInt(),
                    username = System.getenv("KNITTY_TEST_SFTP_USER"),
                    hostKey = System.getenv("KNITTY_TEST_SFTP_FINGERPRINT"),
                ),
            )
            val files = SftpFiles { name ->
                if (name.endsWith("_KEY_FILE")) System.getenv("KNITTY_TEST_SFTP_KEY") else null
            }
            val source = root.resolve("source.bin")
            val bytes = ByteArray(150_000) { (it % 251).toByte() }
            source.writeBytes(bytes)

            files.open(target).use { remote ->
                val directory = root.resolve("transfer").toString()
                remote.mkdir(directory)
                assertFailsWith<IOException> { remote.mkdir(directory) }
                val sent = mutableListOf<Long>()
                remote.upload(source, "$directory/upload.bin", sent::add)
                assertTrue(sent.size > 1)
                assertTrue(sent.zipWithNext().all { (before, after) -> after > before })
                assertEquals(bytes.size.toLong(), sent.last())
                remote.chmod("$directory/upload.bin", 384)
                assertEquals(384, remote.stat("$directory/upload.bin")!!.permissions)
                assertFailsWith<IOException> { remote.upload(source, "$directory/upload.bin") }
                remote.rename("$directory/upload.bin", "$directory/renamed.bin")
                assertNull(remote.stat("$directory/upload.bin"))
                val downloaded = root.resolve("downloaded.bin")
                val received = mutableListOf<Long>()
                remote.download("$directory/renamed.bin", downloaded, bytes.size.toLong(), received::add)
                assertTrue(received.size > 1)
                assertTrue(received.zipWithNext().all { (before, after) -> after > before })
                assertEquals(bytes.size.toLong(), received.last())
                assertContentEquals(bytes, downloaded.readBytes())

                Files.createSymbolicLink(root.resolve("transfer/link"), source)
                assertEquals(RemoteFileType.Unsupported, remote.stat("$directory/link")!!.type)
                remote.removeFile("$directory/link")
                remote.removeFile("$directory/renamed.bin")
                remote.removeDirectory(directory)
            }

            val wrongKey = target.copy(sftp = target.sftp.copy(hostKey = sampleServer.sftp.hostKey))
            assertEquals(
                ServerFailure.HostKeyMismatch,
                assertFailsWith<RemoteFileFailure> { files.open(wrongKey) }.failure,
            )
        }
    }
}
