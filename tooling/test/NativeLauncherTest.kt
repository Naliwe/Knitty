package knitty.tooling

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.*
import kotlin.test.*

@EnabledIfEnvironmentVariable(named = "KNITTY_TEST_JAVA", matches = ".+")
@EnabledOnOs(OS.LINUX)
class NativeLauncherTest {
    @Test
    fun packagedLauncherLoadsTheExecutableJarAndPreservesArgumentsAndExitCodes() =
        stagingDirectory(projectRoot()) { root ->
            val java = Path(System.getenv("KNITTY_TEST_JAVA"))
            requireJava21Runtime(java.parent.parent)
            val inputs = root.resolve("inputs with spaces").createDirectory()
            copyReleaseFile(executableJar(projectRoot()), inputs.resolve("knitty.jar"))

            val image = nativeApplicationImage(
                java.resolveSibling("jpackage"),
                inputs,
                root.resolve("image with spaces"),
                "0.7.0",
            )
            val launcher = image.resolve("bin/knitty")
            val workingDirectory = root.resolve("unrelated working directory").createDirectory()
            val config = root.resolve("user config")
            val library = root.resolve("Steam library with spaces")
            library.resolve("steamapps").createDirectories()

            val (helpCode, help) = launch(launcher, workingDirectory, config, "--help")
            assertEquals(0, helpCode, help)
            assertContains(help, "knitty")

            val (setupCode, setup) = launch(
                launcher, workingDirectory, config,
                "setup", "--steam-library", library.toString(), "--yes",
            )
            assertEquals(0, setupCode, setup)
            val settings = Json.decodeFromString<NativeSettings>(config.resolve("knitty/settings.json").readText())
            assertEquals(library.toRealPath().toString(), settings.steamLibrary)

            val (invalidCode, invalid) = launch(launcher, workingDirectory, config, "not-a-command")
            assertNotEquals(0, invalidCode, invalid)
        }

    private fun launch(launcher: Path, directory: Path, config: Path, vararg arguments: String): Pair<Int, String> {
        val output = directory.resolve("launcher-output.log")
        val process = ProcessBuilder(launcher.toString(), *arguments)
            .directory(directory.toFile())
            .redirectErrorStream(true)
            .redirectOutput(output.toFile())
            .apply { environment()["XDG_CONFIG_HOME"] = config.toString() }
            .start()

        try {
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "Native launcher did not exit: ${output.readText()}")

            return process.exitValue() to output.readText()
        } finally {
            process.destroyForcibly()
        }
    }
}

@Serializable
private data class NativeSettings(val schemaVersion: Int, val steamLibrary: String)
