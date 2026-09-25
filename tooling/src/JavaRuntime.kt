package knitty.tooling

import java.nio.file.Path
import java.util.Properties
import kotlin.io.path.Path
import kotlin.io.path.reader

internal fun defaultJpackage(): Path =
    Path(System.getProperty("java.home")).resolve(if (isWindows) "bin/jpackage.exe" else "bin/jpackage")

internal fun windowsPackagingTool(executable: Path): Path {
    val canonical = executable.toRealPath()
    val release = requireJava21Runtime(canonical.parent.parent)
    val architecture = release.getProperty("OS_ARCH")?.trim('"')
    val operatingSystem = release.getProperty("OS_NAME")?.trim('"')

    require(operatingSystem == "Windows" && architecture in setOf("amd64", "x86_64")) {
        "Select jpackage.exe from a Windows x64 JDK; found OS_NAME=$operatingSystem, OS_ARCH=$architecture."
    }

    return canonical
}

internal fun requireJava21Runtime(directory: Path): Properties {
    val release = Properties().apply {
        directory.resolve("release").reader().use { load(it) }
    }
    val version = release.getProperty("JAVA_VERSION")?.trim('"')

    require(version?.startsWith("21.") == true) {
        "The public Windows package requires Java 21; found JAVA_VERSION=${version ?: "missing"}."
    }

    return release
}
