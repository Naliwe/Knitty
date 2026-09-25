package knitty.games.corekeeper

import knitty.core.model.ApplyFailure
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.EOFException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.NotDirectoryException
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.util.*
import java.util.zip.ZipException
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import kotlin.io.path.*

internal class DeploymentFailure(val failure: ApplyFailure) : Exception()

@Serializable
internal data class NativeManifest(
    val guid: String,
    val name: String,
    val dependencies: List<NativeDependency> = emptyList(),
    val files: List<NativeFile> = emptyList(),
)

@Serializable
internal data class NativeDependency(val modName: String, val required: Boolean)

@Serializable
internal data class NativeFile(val path: String)

internal val manifestJson = Json { ignoreUnknownKeys = true }
internal const val receiptName = ".knitty-ownership.json"

internal suspend fun extractNativeMod(
    archive: Path,
    destination: Path,
): Pair<Path, NativeManifest> = withContext(Dispatchers.IO) {
    val paths = mutableSetOf<String>()
    val spellings = mutableMapOf<String, String>()
    var expanded = 0L
    var entries = 0

    try {
        // Streaming validates entry CRCs; ZipFile also requires a complete central directory.
        ZipFile(archive.toFile()).use { zip ->
            if (zip.size() !in 1..10_000) invalidArchive()
        }

        ZipInputStream(archive.inputStream().buffered()).use { zip ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                currentCoroutineContext().ensureActive()
                val entry = zip.nextEntry ?: break
                if (++entries > 10_000) invalidArchive()

                val name = entry.name.removeSuffix("/")
                if (!safeRelativeName(name)) invalidArchive()
                if (!paths.add(name.lowercase(Locale.ROOT))) invalidArchive()
                val parts = name.split('/')
                for (length in 1..parts.size) {
                    val prefix = parts.take(length).joinToString("/")
                    val previous = spellings.putIfAbsent(prefix.lowercase(Locale.ROOT), prefix)
                    if (previous != null && previous != prefix) invalidArchive()
                }

                val target = destination.resolve(name)
                if (entry.isDirectory) {
                    target.createDirectories()
                } else {
                    target.parent.createDirectories()
                    target.outputStream(CREATE_NEW).use { output ->
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = zip.read(buffer)
                            if (count < 0) break
                            expanded += count
                            if (expanded > 1024L * 1024 * 1024) invalidArchive()
                            output.write(buffer, 0, count)
                        }
                    }
                }
                zip.closeEntry()
            }
        }

        val manifests = Files.walk(destination).use { paths ->
            paths.filter { it.isRegularFile() && it.name == "ModManifest.json" }.toList()
        }
        val manifestPath = manifests.singleOrNull()
            ?: invalidArchive()

        val root = manifestPath.parent
        if (root != destination) {
            if (root.parent != destination || destination.listDirectoryEntries().singleOrNull() != root) {
                invalidArchive()
            }
        }

        val manifest = readManifest(manifestPath)
        if (manifest.files.any { !safeRelativeName(it.path) || !root.resolve(it.path).isRegularFile() }) {
            invalidArchive()
        }

        root to manifest
    } catch (failure: DeploymentFailure) {
        throw failure
    } catch (_: ZipException) {
        invalidArchive()
    } catch (_: EOFException) {
        invalidArchive()
    } catch (_: FileAlreadyExistsException) {
        invalidArchive()
    } catch (_: NotDirectoryException) {
        invalidArchive()
    } catch (_: IllegalArgumentException) {
        invalidArchive()
    }
}

internal fun readManifest(path: Path): NativeManifest {
    if (path.fileSize() > 1024 * 1024) invalidArchive()

    val manifest = try {
        manifestJson.decodeFromString<NativeManifest>(path.readText())
    } catch (_: SerializationException) {
        invalidArchive()
    }
    if (manifest.name.isBlank() || manifest.guid.isBlank() || manifest.dependencies.any { it.modName.isBlank() }) {
        invalidArchive()
    }

    return manifest
}

private val reservedFilename = Regex("(?i)(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])")

private fun safeRelativeName(name: String): Boolean {
    if (name.isEmpty() || name.length > 1024 || name.contains('\\')) return false
    val parts = name.split('/')
    return parts.size <= 32 && parts.all { part ->
        val ordinaryName = part.isNotEmpty() && part != "." && part != ".."
        val reservedName = part.equals(receiptName, ignoreCase = true) ||
            reservedFilename.matches(part.substringBefore('.'))
        val unsafeCharacters = part.any { it.isISOControl() || it in ":*?\"<>|" }
        ordinaryName && !reservedName && !unsafeCharacters && !part.endsWith('.') && !part.endsWith(' ')
    }
}

private fun invalidArchive(): Nothing = throw DeploymentFailure(ApplyFailure.InvalidArchive)

internal fun validateNativeDependencies(manifests: List<NativeManifest>) {
    for ((_, name, dependencies) in manifests) {
        for ((modName, required) in dependencies) {
            if (required && manifests.none { it.name.equals(modName, ignoreCase = true) }) {
                throw DeploymentFailure(ApplyFailure.MissingDependency(name, modName))
            }
        }
    }
}
