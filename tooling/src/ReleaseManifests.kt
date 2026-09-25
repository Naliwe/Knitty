package knitty.tooling

import java.nio.file.Path
import java.time.LocalDate
import java.util.zip.ZipFile
import kotlin.io.path.*

internal class ReleaseManifests(private val root: Path) {
    fun generate(
        version: String,
        license: String = "MIT",
        artifacts: Path = root.resolve("dist"),
        output: Path = root.resolve("dist/manifests"),
        date: String = LocalDate.now().toString(),
        target: String = "all",
    ): ManifestDirectories {
        releaseVersion(version)
        require(target in setOf("all", "aur", "winget")) { "Select all, aur, or winget manifests." }
        require(Regex("[A-Za-z0-9][A-Za-z0-9.+-]*").matches(license)) { "Supply a single SPDX license identifier." }
        require(root.resolve("LICENSE").isRegularFile()) { "LICENSE must be present." }

        val values = mapOf("VERSION" to version, "LICENSE" to license, "DATE" to LocalDate.parse(date).toString())
        val jvmHash = if (target != "winget") sha256(artifacts.resolve("knitty-$version-jvm.tar.gz")) else null
        val sourceHash = if (target != "winget") sha256(artifacts.resolve("knitty-$version-source.tar.gz")) else null
        val windowsHash =
            if (target != "aur") windowsHash(artifacts.resolve("knitty-$version-windows-x64.zip")) else null

        val aur = buildList {
            for ((name, hash) in listOf("knitty" to sourceHash, "knitty-bin" to jvmHash)) {
                if (hash == null) continue

                val directory = output.resolve("aur/$name").createDirectories()
                val template = root.resolve("packaging/aur/$name.PKGBUILD.in").readText()
                directory.resolve("PKGBUILD").writeText(renderTemplate(template, values + ("SHA256" to hash)))
                add(directory)
            }
        }

        val winget = windowsHash?.let { hash ->
            val directory = output.resolve("winget/manifests/n/Naliwe/Knitty/$version").createDirectories()
            for (template in root.resolve("packaging/winget").listDirectoryEntries("*.in")) {
                val contents = renderTemplate(template.readText(), values + ("SHA256" to hash))
                directory.resolve(template.name.removeSuffix(".in")).writeText(contents)
            }
            directory
        }

        return ManifestDirectories(aur, winget)
    }

    fun sourceInfo(directory: Path) {
        val temporary = directory.resolve(".SRCINFO.tmp")
        try {
            val process = ProcessBuilder("makepkg", "--printsrcinfo")
                .directory(directory.toFile())
                .redirectOutput(temporary.toFile())
                .redirectError(ProcessBuilder.Redirect.INHERIT)
                .start()
            check(process.waitFor() == 0) { "makepkg --printsrcinfo failed." }
            temporary.moveTo(directory.resolve(".SRCINFO"), overwrite = true)
        } finally {
            temporary.deleteIfExists()
        }
    }

    private fun windowsHash(artifact: Path): String {
        ZipFile(artifact.toFile()).use { archive ->
            for (name in listOf("knitty/knitty.exe", "knitty/app/knitty.jar", "knitty/runtime/release")) {
                require(archive.getEntry(name) != null) { "Windows artifact lacks $name." }
            }
        }

        return sha256(artifact).uppercase()
    }
}

internal data class ManifestDirectories(val aur: List<Path>, val winget: Path?)

internal fun renderTemplate(template: String, values: Map<String, String>): String {
    val rendered = values.entries.fold(template) { text, (name, value) -> text.replace("@$name@", value) }
    require(!Regex("@[A-Z0-9_]+@").containsMatchIn(rendered)) { "Unresolved release template value." }

    return rendered
}
