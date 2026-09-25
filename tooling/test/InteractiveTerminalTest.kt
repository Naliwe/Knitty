package knitty.tooling

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import kotlin.io.path.*
import kotlin.test.*

@EnabledIfEnvironmentVariable(named = "KNITTY_TEST_JAVA", matches = ".+")
@EnabledOnOs(OS.LINUX, OS.MAC)
class InteractiveTerminalTest {
    @Test
    fun packagedHelpInitializesRealTerminalOnJava21() = stagingDirectory(projectRoot()) { root ->
        TerminalSession(java, listOf("--help"), root).use { terminal ->
            assertContains(terminal.finish(), "knitty")
        }
    }

    @Test
    fun confirmationAcceptsNoWithoutWritingSettings() = stagingDirectory(projectRoot()) { root ->
        root.resolve("steamapps").createDirectory()
        val config = root.resolve("config")

        TerminalSession(java, listOf("setup", "--steam-library", root.toString()), root, config).use { terminal ->
            terminal.answer("Save this library for CLI and TUI?", "n")
            assertContains(terminal.finish(), "Cancelled. Settings unchanged.")
        }

        assertFalse(config.resolve("knitty/settings.json").exists())
    }

    @Test
    fun authenticationHidesKeyAndSavesOnlyInUserConfiguration() = stagingDirectory(projectRoot()) { root ->
        TerminalSession(java, listOf("auth"), root).use { terminal ->
            terminal.answer("API path (https://u-...modapi.io/v1):", "https://u-123.modapi.io/v1")
            terminal.answer("API key (hidden):", "fixtureSecret123", hidden = true)
            terminal.answer("Save or replace your mod.io access?", "y")
            val output = terminal.finish()

            assertContains(output, "Saved for CLI and TUI")
            assertFalse("fixtureSecret123" in output)
        }

        val saved = root.resolve("knitty/modio.env")
        assertContains(saved.readText(), "KNITTY_MODIO_API_KEY=fixtureSecret123")
        assertEquals(
            setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
            Files.getPosixFilePermissions(saved),
        )
    }

    @Test
    fun syncProgressFinishesBeforeTheSuccessMessage() = stagingDirectory(projectRoot()) { root ->
        root.resolve("CoreKeeper").writeText("")
        val mods = root.resolve("CoreKeeper_Data/StreamingAssets/Mods").createDirectories()
        mods.resolve("manual.txt").writeText("preserved")
        root.resolve("knitty.yaml").writeText("schemaVersion: 1\nid: progress-test\ngame: core-keeper\nmods: []\n")
        root.resolve("knitty.lock")
            .writeText(Json.encodeToString(EmptyLock(2, "progress-test", "core-keeper", emptyList())))
        val arguments =
            listOf("sync", "core-keeper", "--profile", root.toString(), "--installation", root.toString(), "--yes")

        TerminalSession(java, arguments, root, root.resolve("config")).use { terminal ->
            val output = terminal.finish()
            for (message in listOf("Checking installation", "Preparing mods", "Installing mods", "[")) {
                assertContains(output, message)
            }
            assertTrue(output.indexOf("Profile synchronized") > output.indexOf("Installing mods"))
        }

        assertEquals("preserved", mods.resolve("manual.txt").readText())
    }

    @Test
    fun typingAndSwitchingViewsDoNotEraseTheTerminal() {
        for (type in listOf("xterm-kitty", "xterm-256color")) {
            stagingDirectory(projectRoot()) { root ->
                TerminalSession(java, listOf("tui", "core-keeper"), root, terminal = type).use { terminal ->
                    terminal.frame("MOD CATALOG")
                    for (key in listOf("a", "b", "c", "\u007f", "\t", "?", "\u001b")) {
                        terminal.send(key)
                        val frame = terminal.frame()
                        assertFalse("\u001b[2J" in frame, "$type: a key press erased the screen")
                        assertFalse("\u001b[3J" in frame, "$type: a key press erased scrollback")
                    }
                    terminal.send("q")
                    terminal.finish()
                }
            }
        }
    }

    companion object {
        private val java: String = System.getenv("KNITTY_TEST_JAVA").orEmpty()

        @BeforeAll
        @JvmStatic
        fun checkJavaAndArtifact() {
            val process = ProcessBuilder(java, "-version").redirectErrorStream(true).start()
            val version = process.inputStream.bufferedReader().use { it.readText() }

            assertEquals(0, process.waitFor())
            assertContains(version, "version \"21.", message = "Newer Java versions hide the Mordant regression.")
            assertTrue(
                executableJar(projectRoot()).isRegularFile(),
                "Build the executable JAR before running terminal checks.",
            )
        }
    }
}

@Serializable
private data class EmptyLock(
    val schemaVersion: Int,
    val profileId: String,
    val game: String,
    val packages: List<String>,
)
