package knitty.tooling

import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.security.MessageDigest
import kotlin.io.path.*

internal fun projectRoot(start: Path = Path(".").toAbsolutePath().normalize()): Path =
    generateSequence(start) { it.parent }
        .firstOrNull { it.resolve("project.yaml").isRegularFile() }
        ?: error("Run from the Knitty project directory.")

internal fun executableJar(root: Path): Path =
    root.resolve("build/tasks/_knitty_executableJarJvm/knitty-jvm-executable.jar")

internal fun sha256(path: Path): String {
    val digest = MessageDigest.getInstance("SHA-256")

    path.inputStream().buffered().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break

            digest.update(buffer, 0, count)
        }
    }

    return digest.digest().toHexString()
}

internal fun copyReleaseFile(source: Path, destination: Path) {
    require(source.isRegularFile(NOFOLLOW_LINKS)) { "Missing regular release input: ${source.name}" }
    source.copyTo(destination)
}

internal fun <T> stagingDirectory(root: Path, action: (Path) -> T): T {
    val build = root.resolve("build").createDirectories()
    val directory = Files.createTempDirectory(build, "release-")

    try {
        return action(directory)
    } finally {
        Files.walk(directory).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach { path ->
                // Git objects can be read-only on Windows, which prevents deleting temporary repositories.
                if (isWindows && path.isRegularFile(NOFOLLOW_LINKS)) {
                    path.setAttribute("dos:readonly", false, NOFOLLOW_LINKS)
                }

                path.deleteExisting()
            }
        }
    }
}

internal fun publishArtifact(pending: Path, artifact: Path): Path {
    artifact.parent.createDirectories()
    val temporary = Files.createTempFile(artifact.parent, ".release-", ".tmp")

    try {
        pending.copyTo(temporary, overwrite = true)
        Files.move(temporary, artifact, ATOMIC_MOVE, REPLACE_EXISTING)
    } finally {
        temporary.deleteIfExists()
    }

    artifact.resolveSibling("${artifact.name}.sha256")
        .writeText("${sha256(artifact)}  ${artifact.name}\n")

    return artifact
}

internal fun runProcess(
    command: List<String>,
    directory: Path,
    environment: Map<String, String> = emptyMap(),
) {
    val process = ProcessBuilder(command)
        .directory(directory.toFile())
        .inheritIO()
        .apply { environment().putAll(environment) }
        .start()

    check(process.waitFor() == 0) { "Command failed: ${command.first()}" }
}

internal val isWindows: Boolean = System.getProperty("os.name").startsWith("Windows")

internal fun kotlinCommand(root: Path, vararg arguments: String): List<String> =
    if (isWindows) {
        listOf("cmd", "/c", root.resolve("kotlin.bat").toString(), *arguments)
    } else {
        listOf(root.resolve("kotlin").toString(), *arguments)
    }
