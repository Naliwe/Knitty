package knitty.tooling

import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.archivers.zip.ZipFile
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.*

internal class ReleaseArchives {
    fun tar(directory: Path, output: Path) {
        TarArchiveOutputStream(GZIPOutputStream(output.outputStream())).use { archive ->
            for (path in files(directory)) {
                val name = "${directory.name}/${path.relativeTo(directory).invariantSeparatorsPathString}"
                val entry = TarArchiveEntry(name).apply {
                    size = path.fileSize()
                    mode = if (path.name == "knitty") 493 else 420
                    setUserId(0L)
                    setGroupId(0L)
                    userName = ""
                    groupName = ""
                    setModTime(0L)
                }

                archive.putArchiveEntry(entry)
                path.inputStream().use { it.copyTo(archive) }
                archive.closeArchiveEntry()
            }
        }
    }

    fun zip(directory: Path, output: Path) {
        ZipOutputStream(output.outputStream().buffered()).use { archive ->
            for (path in files(directory)) {
                val name = "${directory.name}/${path.relativeTo(directory).invariantSeparatorsPathString}"
                archive.putNextEntry(ZipEntry(name).apply { time = 0 })
                path.inputStream().use { it.copyTo(archive) }
                archive.closeEntry()
            }
        }
    }

    fun extractRuntime(archive: Path, destination: Path, checksum: String) {
        require(sha256(archive) == checksum) { "Runtime checksum mismatch." }

        ZipFile.builder().setPath(archive).get().use { source ->
            val entries = source.entries.asSequence().toList()
            val paths = entries.map { entry ->
                val parts = entry.name.removeSuffix("/").split('/')
                require(
                    parts.all { it.isNotEmpty() && it != "." && it != ".." } &&
                        '\\' !in entry.name && ':' !in entry.name && !entry.isUnixSymlink,
                ) { "Unsafe path in runtime ZIP." }

                parts
            }
            require(paths.map { it.first() }.distinct().size == 1) {
                "Expected one runtime directory in ZIP."
            }

            // Validate every entry before creating anything in the extraction directory.
            for ((entry, parts) in entries.zip(paths)) {
                val target = parts.drop(1).fold(destination) { path, part -> path.resolve(part) }
                if (entry.isDirectory) {
                    target.createDirectories()
                } else {
                    target.parent.createDirectories()
                    source.getInputStream(entry).use { input ->
                        target.outputStream().use { input.copyTo(it) }
                    }
                }
            }
        }

        require(destination.resolve("bin/java.exe").isRegularFile()) {
            "Windows runtime does not contain bin/java.exe."
        }
    }

    private fun files(directory: Path): List<Path> = Files.walk(directory).use { paths ->
        paths.filter { it.isRegularFile() }.sorted().toList()
    }
}
