package knitty.settings

import knitty.core.application.DefaultConfigureModio
import knitty.core.model.ModioAccess
import knitty.core.model.ModioAccessFailure
import knitty.core.model.ModioAccessOutcome
import knitty.core.model.ModioSetupOutcome
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.*
import kotlin.test.*

class ModioAccessTest {
    @Test
    fun rejectsInvalidEndpointsAndInjectedAssignmentsBeforeSaving() = runTest {
        val setup = DefaultConfigureModio { error("Must not save invalid access") }
        for (path in listOf(
            "https://example.com/v1",
            "http://u-123.modapi.io/v1",
            "https://u-123.modapi.io/v1?secret",
        )) {
            assertEquals(ModioAccessOutcome.Invalid(ModioAccessFailure.InvalidEndpoint), setup.plan(path, "secret"))
        }
        for ((key, failure) in listOf(
            "" to ModioAccessFailure.EmptyKey,
            "one\nOTHER=two" to ModioAccessFailure.InvalidKeyCharacters,
            "'quoted'" to ModioAccessFailure.InvalidKeyCharacters,
            "secret\u0016" to ModioAccessFailure.InvalidKeyCharacters,
            "x".repeat(513) to ModioAccessFailure.KeyTooLong,
        )) {
            assertEquals(ModioAccessOutcome.Invalid(failure), setup.plan("https://u-123.modapi.io/v1", key))
        }
        assertFalse(
            assertIs<ModioAccessOutcome.Valid>(ModioAccess.parse("https://u-123.modapi.io/v1", "secret")).toString()
                .contains("secret"),
        )
    }

    @Test
    fun savesOutsideProfileAndLoadsWithExplicitPrecedence() = runTest {
        val root = createTempDirectory("knitty-access-")
        try {
            val config = root.resolve("config").createDirectory()
            val profile = root.resolve("profile").createDirectory()
            val file = config.resolve("modio.env")
            val setup = DefaultConfigureModio(FileModioAccess(file))

            assertEquals(ModioSetupOutcome.Saved, setup.save(access("https://u-123.modapi.io/v1/", "first")))
            assertEquals(ModioSetupOutcome.Saved, setup.save(access("https://u-123.modapi.io/v1", "second_-./+=#")))
            val defaults = assertIs<EnvironmentOutcome.Loaded>(
                ProfileEnvironment.load(config, { null }, fileName = "modio.env"),
            ).environment
            assertEquals("second_-./+=#", defaults["KNITTY_MODIO_API_KEY"])
            assertEquals(listOf(file), config.listDirectoryEntries())
            assertTrue(profile.listDirectoryEntries().isEmpty())

            profile.resolve(".env").writeText("KNITTY_MODIO_API_KEY=profile\n")
            val loaded =
                assertIs<EnvironmentOutcome.Loaded>(ProfileEnvironment.load(profile, { null }, defaults)).environment
            assertEquals("profile", loaded["KNITTY_MODIO_API_KEY"])
            assertEquals("https://u-123.modapi.io/v1", loaded["KNITTY_MODIO_API_PATH"])
            val overridden =
                assertIs<EnvironmentOutcome.Loaded>(ProfileEnvironment.load(profile, { "" }, defaults)).environment
            assertEquals("", overridden["KNITTY_MODIO_API_KEY"])

            if (Files.getFileAttributeView(file, PosixFileAttributeView::class.java) != null) {
                assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(file))
            }
        } finally {
            Files.walk(root).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach { it.deleteIfExists() }
            }
        }
    }

    @Test
    fun failedWriteDoesNotReplaceUnrelatedFiles() = runTest {
        val root = createTempDirectory("knitty-access-")
        try {
            val parent = root.resolve("file").apply { writeText("preserved") }
            val setup = DefaultConfigureModio(FileModioAccess(parent.resolve("modio.env")))

            assertEquals(
                ModioSetupOutcome.FilesystemFailure,
                setup.save(access("https://u-123.modapi.io/v1", "secret")),
            )
            assertEquals("preserved", parent.readText())
        } finally {
            Files.walk(root).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach { it.deleteIfExists() }
            }
        }
    }
}

private fun access(path: String, key: String): ModioAccess =
    assertIs<ModioAccessOutcome.Valid>(ModioAccess.parse(path, key)).access
