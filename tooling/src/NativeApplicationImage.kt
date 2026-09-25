package knitty.tooling

import java.nio.file.Path

internal fun nativeApplicationImage(jpackage: Path, inputs: Path, destination: Path, version: String): Path {
    val command = buildList {
        // The executable JAR's manifest selects its loader for nested application classes and dependencies.
        addAll(
            listOf(
                jpackage.toString(), "--type", "app-image", "--name", "knitty", "--app-version", version,
                "--input", inputs.toString(), "--main-jar", "knitty.jar",
                "--dest", destination.toString(),
                // SSH, TLS providers and terminal libraries load JDK modules dynamically.
                "--add-modules", "ALL-MODULE-PATH", "--java-options", "--enable-native-access=ALL-UNNAMED",
            ),
        )
        if (isWindows) add("--win-console")
    }

    runProcess(command, inputs.parent)

    return destination.resolve("knitty")
}
