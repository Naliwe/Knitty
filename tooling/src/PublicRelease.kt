package knitty.tooling

import java.nio.file.Path
import kotlin.io.path.*

internal fun releaseVersion(value: String): String {
    require(Regex("[0-9]+\\.[0-9]+\\.[0-9]+").matches(value)) {
        "Use a stable numeric version such as 0.7.0."
    }

    return value
}

internal class PublicRelease(
    private val root: Path,
    private val archives: ReleaseArchives = ReleaseArchives(),
) {
    fun assemble(
        version: String,
        platform: String,
        jpackage: Path = defaultJpackage(),
        jar: Path = executableJar(root),
        output: Path = root.resolve("dist"),
    ): Path {
        releaseVersion(version)

        return stagingDirectory(root) { staging ->
            val inputs = staging.resolve("knitty-$version").createDirectory()
            copyReleaseFile(jar, inputs.resolve("knitty.jar"))
            copyReleaseFile(root.resolve("LICENSE"), inputs.resolve("LICENSE"))
            copyReleaseFile(root.resolve("THIRD-PARTY-NOTICES.md"), inputs.resolve("THIRD-PARTY-NOTICES.md"))
            copyReleaseFile(root.resolve("packaging/README.md"), inputs.resolve("README.md"))

            val pending = when (platform) {
                "jvm" -> {
                    copyReleaseFile(root.resolve("packaging/aur/knitty"), inputs.resolve("knitty"))
                    staging.resolve("knitty-$version-jvm.tar.gz").also { archives.tar(inputs, it) }
                }

                "windows-x64" -> {
                    val image = windowsImage(inputs, staging, version, jpackage)
                    staging.resolve("knitty-$version-windows-x64.zip").also { archives.zip(image, it) }
                }

                else -> error("Unsupported release platform: $platform")
            }

            publishArtifact(pending, output.resolve(pending.name))
        }
    }

    private fun windowsImage(inputs: Path, staging: Path, version: String, jpackage: Path): Path {
        require(isWindows) { "Build the Windows native launcher on Windows with a Java 21 x64 JDK." }
        val packagingTool = windowsPackagingTool(jpackage)

        val image = nativeApplicationImage(packagingTool, inputs, staging.resolve("image"), version)
        // jlink retains JAVA_VERSION but can omit the source JDK's OS_ARCH and OS_NAME.
        requireJava21Runtime(image.resolve("runtime"))

        return image
    }
}
