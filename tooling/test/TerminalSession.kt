package knitty.tooling

import com.pty4j.PtyProcessBuilder
import com.pty4j.unix.UnixPtyProcess
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Path
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

internal class TerminalSession(
    java: String,
    arguments: List<String>,
    directory: Path,
    config: Path = directory,
    terminal: String = "xterm-256color",
) : AutoCloseable {
    private val command = listOf(
        java, "--enable-native-access=ALL-UNNAMED", "-jar", executableJar(projectRoot()).toString(),
    ) + arguments
    private val environment = System.getenv().filterKeys { !it.startsWith("KNITTY_") } + mapOf(
        "TERM" to terminal,
        "XDG_CONFIG_HOME" to config.toString(),
    )
    private val process = PtyProcessBuilder(command.toTypedArray())
        .setDirectory(directory.toString())
        .setEnvironment(environment)
        .setInitialColumns(100)
        .setInitialRows(24)
        .setRedirectErrorStream(true)
        .start()
    private val chunks = LinkedBlockingQueue<ByteArray>()
    private val output = ByteArrayOutputStream()
    private var answeredQueries = 0
    private val reader = thread(name = "terminal-output", isDaemon = true) {
        try {
            val buffer = ByteArray(8192)
            while (true) {
                val count = process.inputStream.read(buffer)
                if (count < 0) break
                chunks.put(buffer.copyOf(count))
            }
        } catch (_: IOException) {
            // A Unix PTY can signal EIO rather than EOF when its child exits.
        } finally {
            chunks.put(byteArrayOf())
        }
    }

    val transcript: String get() = output.toString(Charsets.UTF_8)

    fun frame(expected: String? = null): String {
        val frame = ByteArrayOutputStream()
        val started = TimeSource.Monotonic.markNow()

        while (started.elapsedNow() < 10.seconds) {
            val chunk = chunks.poll(200, TimeUnit.MILLISECONDS)
            if (chunk == null || chunk.isEmpty()) {
                val text = frame.toString(Charsets.UTF_8)
                if (text.isNotEmpty() && (expected == null || expected in text)) return text
                if (chunk != null) break
                continue
            }

            frame.write(chunk)
            receive(chunk)
        }

        error("Terminal did not produce expected frame ($expected): $transcript")
    }

    fun send(text: String) {
        process.outputStream.write(text.toByteArray())
        process.outputStream.flush()
    }

    fun answer(prompt: String, answer: String, hidden: Boolean = false) {
        frame(prompt)
        if (hidden) awaitHiddenInput()
        send(answer + "\n")
    }

    fun finish(): String {
        val started = TimeSource.Monotonic.markNow()
        while (process.isAlive && started.elapsedNow() < 30.seconds) {
            chunks.poll(100, TimeUnit.MILLISECONDS)?.let(::receive)
        }
        assertTrue(process.waitFor(1, TimeUnit.SECONDS), "Interactive process timed out: $transcript")
        reader.join(1000)
        while (true) receive(chunks.poll() ?: break)

        assertEquals(0, process.exitValue(), transcript)
        return transcript
    }

    private fun receive(chunk: ByteArray) {
        output.write(chunk)
        val queries = Regex("\u001b\\[6n").findAll(transcript).count()
        while (answeredQueries < queries) {
            send("\u001b[24;100R")
            answeredQueries++
        }
    }

    private fun awaitHiddenInput() {
        val device = (process as UnixPtyProcess).pty.slaveName
        val started = TimeSource.Monotonic.markNow()
        while (started.elapsedNow() < 5.seconds) {
            val state = ProcessBuilder("stty", "-a")
                .redirectInput(File(device))
                .redirectError(ProcessBuilder.Redirect.INHERIT)
                .start()
            val flags = state.inputStream.bufferedReader().use { it.readText() }
            check(state.waitFor() == 0) { "Cannot inspect terminal echo state." }
            if (Regex("(?:^|\\s)-echo(?:\\s|;|$)").containsMatchIn(flags)) return
            Thread.sleep(10)
        }

        error("Password input did not disable terminal echo.")
    }

    override fun close() {
        if (process.isAlive) process.destroyForcibly().waitFor()
        process.destroy()
        reader.join(1000)
    }
}
