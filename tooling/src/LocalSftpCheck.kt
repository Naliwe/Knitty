package knitty.tooling

import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.*

internal class LocalSftpCheck(private val root: Path) {
    fun run() {
        require(!isWindows) { "The local OpenSSH protocol check requires Linux or macOS." }
        val sshd = System.getenv("PATH").split(':')
            .map { Path(it).resolve("sshd") }
            .firstOrNull { it.isExecutable() }
            ?: error("Install OpenSSH server to run this local protocol check.")

        stagingDirectory(root) { directory ->
            for (name in listOf("host", "client")) {
                runProcess(
                    listOf("ssh-keygen", "-q", "-t", "ed25519", "-N", "", "-f", directory.resolve(name).toString()),
                    root,
                )
            }

            val fingerprint = capture("ssh-keygen", "-lf", directory.resolve("host.pub").toString(), "-E", "sha256")
                .split(Regex("\\s+"))[1]
            val port = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }
            val config = directory.resolve("sshd_config")
            config.writeText(
                """
                ListenAddress 127.0.0.1
                Port $port
                HostKey "${directory.resolve("host")}"
                PidFile "${directory.resolve("sshd.pid")}"
                AuthorizedKeysFile "${directory.resolve("client.pub")}"
                StrictModes no
                UsePAM no
                PasswordAuthentication no
                KbdInteractiveAuthentication no
                PubkeyAuthentication yes
                AuthenticationMethods publickey
                Subsystem sftp internal-sftp
                LogLevel ERROR
                """.trimIndent() + "\n",
            )

            val log = directory.resolve("sshd.log")
            val daemon = ProcessBuilder(sshd.toString(), "-D", "-e", "-f", config.toString())
                .redirectErrorStream(true)
                .redirectOutput(log.toFile())
                .start()

            try {
                awaitServer(daemon, port, log)
                val environment = mapOf(
                    "KNITTY_TEST_SFTP" to "1",
                    "KNITTY_TEST_SFTP_ROOT" to directory.toString(),
                    "KNITTY_TEST_SFTP_PORT" to port.toString(),
                    "KNITTY_TEST_SFTP_USER" to capture("id", "-un").trim(),
                    "KNITTY_TEST_SFTP_FINGERPRINT" to fingerprint,
                    "KNITTY_TEST_SFTP_KEY" to directory.resolve("client").toString(),
                )
                runProcess(kotlinCommand(root, "test", "-m", "knitty"), root, environment)
            } finally {
                daemon.destroy()
                if (!daemon.waitFor(10, TimeUnit.SECONDS)) {
                    daemon.destroyForcibly().waitFor()
                }
            }
        }
    }

    private fun awaitServer(daemon: Process, port: Int, log: Path) {
        repeat(50) {
            check(daemon.isAlive) { log.readText() }
            try {
                Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 100) }
                return
            } catch (_: IOException) {
                Thread.sleep(100)
            }
        }

        error("Local OpenSSH server did not become ready.")
    }

    private fun capture(vararg command: String): String {
        val process = ProcessBuilder(*command).redirectError(ProcessBuilder.Redirect.INHERIT).start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        check(process.waitFor() == 0) { "Command failed: ${command.first()}" }

        return output
    }
}
