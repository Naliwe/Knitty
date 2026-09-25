package knitty.settings

import knitty.core.application.DefaultSetupSteamLibrary
import knitty.core.model.*
import knitty.games.corekeeper.CoreKeeperInstallation
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

class SteamLibrarySetupTest {
    @Test
    fun setupExpandsHomeValidatesLibraryAndSavesOnlyAfterApply() = runTest {
        val home = createTempDirectory("knitty-setup-")
        try {
            val library = home.resolve("Games/SteamLibrary")
            library.resolve("steamapps").createDirectories()
            val file = home.resolve("config/knitty/settings.json")
            val settings = FileSteamLibrarySettings(file)
            val setup = DefaultSetupSteamLibrary(SteamLibraryDirectory(home), settings)

            val planned = assertIs<SetupPlanOutcome.Planned>(setup.plan("~/Games/SteamLibrary/"))
            assertEquals(library.toRealPath().toString(), planned.plan.directory)
            assertFalse(file.exists())
            assertEquals(SaveSetupOutcome.Saved, setup.save(planned.plan))
            assertEquals(SteamLibraryOutcome.Loaded(planned.plan.directory), FileSteamLibrarySettings(file).read())
            assertEquals(listOf(file), file.parent.listDirectoryEntries())
            assertFalse(home.resolve("knitty.lock").exists())
        } finally {
            deleteTree(home)
        }
    }

    @Test
    fun invalidOrRemovedLibraryNeverReplacesSavedSettings() = runTest {
        val home = createTempDirectory("knitty-setup-")
        try {
            val library = home.resolve("library")
            val steamapps = library.resolve("steamapps").createDirectories()
            val settings = FileSteamLibrarySettings(home.resolve("settings.json"))
            val setup = DefaultSetupSteamLibrary(SteamLibraryDirectory(home), settings)
            val plan = assertIs<SetupPlanOutcome.Planned>(setup.plan(library.toString())).plan
            assertEquals(SaveSetupOutcome.Saved, setup.save(plan))

            for (invalid in listOf("", home.toString(), "~/missing", "invalid\u0000path")) {
                assertEquals(SetupPlanOutcome.Failed(SetupFailure.InvalidLibrary), setup.plan(invalid))
            }
            steamapps.deleteExisting()
            assertEquals(SaveSetupOutcome.Failed(SetupFailure.InvalidLibrary), setup.save(plan))
            assertEquals(SteamLibraryOutcome.Loaded(plan.directory), settings.read())
        } finally {
            deleteTree(home)
        }
    }

    @Test
    fun malformedOrFutureSettingsAreReportedAndPreserved() = runTest {
        val root = createTempDirectory("knitty-settings-")
        try {
            val file = root.resolve("settings.json")
            val settings = FileSteamLibrarySettings(file)
            assertEquals(SteamLibraryOutcome.Loaded(null), settings.read())

            for (contents in listOf(
                "broken",
                """{"schemaVersion":2,"steamLibrary":"/games"}""",
                """{"steamLibrary":"relative"}""",
            )) {
                file.writeText(contents)
                assertEquals(SteamLibraryOutcome.Failed(SetupFailure.InvalidSettings), settings.read())
                assertEquals(SaveSetupOutcome.Failed(SetupFailure.InvalidSettings), settings.save("/replacement"))
                assertEquals(contents, file.readText())
            }
        } finally {
            deleteTree(root)
        }
    }

    @Test
    fun failedSettingsWritePreservesExistingFilesystem() = runTest {
        val root = createTempDirectory("knitty-settings-")
        try {
            val parent = root.resolve("not-a-directory")
            parent.writeText("keep")
            val settings = FileSteamLibrarySettings(parent.resolve("settings.json"))

            assertEquals(SaveSetupOutcome.Failed(SetupFailure.FilesystemFailure), settings.save("/games"))
            assertEquals("keep", parent.readText())
        } finally {
            deleteTree(root)
        }
    }

    @Test
    fun savedLibraryFeedsSharedLocatorWithExplicitAndEnvironmentOverrides() = runTest {
        val root = createTempDirectory("knitty-locator-")
        try {
            val library = root.resolve("SteamLibrary")
            val game = library.resolve("steamapps/common/Core Keeper")
            game.resolve("CoreKeeper_Data/StreamingAssets").createDirectories()
            game.resolve("CoreKeeper").writeText("")
            val settingsFile = root.resolve("settings.json")
            val settings = FileSteamLibrarySettings(settingsFile)
            settings.save(library.toString())
            val gameId = GameId("core-keeper")
            val locator = CoreKeeperInstallation(home = root, settings = settings)

            val found = assertIs<InstallationOutcome.Found>(locator.locate(gameId, null))
            assertEquals(game.toRealPath().toString(), found.installation.directory)
            assertFalse(Path(found.installation.modsDirectory).exists())
            assertEquals(
                InstallationOutcome.Failed(ApplyFailure.InstallationNotFound),
                locator.locate(gameId, root.resolve("missing").toString()),
            )

            val environmentOverride = CoreKeeperInstallation(root.resolve("missing").toString(), root, settings)
            assertEquals(
                InstallationOutcome.Failed(ApplyFailure.InstallationNotFound),
                environmentOverride.locate(gameId, null),
            )
            assertIs<InstallationOutcome.Found>(environmentOverride.locate(gameId, game.toString()))

            settingsFile.writeText("corrupt")
            assertEquals(InstallationOutcome.Failed(ApplyFailure.InvalidSettings), locator.locate(gameId, null))
            assertIs<InstallationOutcome.Found>(locator.locate(gameId, game.toString()))
        } finally {
            deleteTree(root)
        }
    }

    @Test
    fun settingsLocationUsesAbsoluteXdgDirectoryOrHomeFallback() {
        val home = Path("home/example").toAbsolutePath()
        val custom = Path("custom").toAbsolutePath()

        assertEquals(custom.resolve("knitty/settings.json"), localSettingsPath(home, custom.toString()))
        assertEquals(home.resolve(".config/knitty/settings.json"), localSettingsPath(home, null))
        assertEquals(home.resolve(".config/knitty/settings.json"), localSettingsPath(home, "relative"))
    }
}

private fun deleteTree(root: Path) {
    Files.walk(root).use { paths ->
        paths.sorted(Comparator.reverseOrder()).forEach { it.deleteIfExists() }
    }
}
