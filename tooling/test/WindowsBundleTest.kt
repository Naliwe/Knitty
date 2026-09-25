package knitty.tooling

import kotlin.io.path.*
import kotlin.test.*

class WindowsBundleTest {
    @Test
    fun profileExportExcludesCredentialsAndOtherLocalFiles() = stagingDirectory(projectRoot()) { root ->
        val source = root.resolve("source").createDirectory()
        for (name in listOf("knitty.yaml", "knitty.lock", ".env", "settings.json", "modio.json")) {
            source.resolve(name).writeText(name)
        }
        source.resolve(".git").createDirectory()
        val output = root.resolve("output")

        copyProfile(source, output)

        assertEquals(setOf("knitty.yaml", "knitty.lock"), output.listDirectoryEntries().map { it.name }.toSet())
        assertEquals("knitty.lock", output.resolve("knitty.lock").readText())
    }

    @Test
    fun incompleteProfileIsRejectedBeforeCopying() = stagingDirectory(projectRoot()) { root ->
        root.resolve("knitty.yaml").writeText("profile")

        val failure = assertFailsWith<IllegalArgumentException> { copyProfile(root, root.resolve("output")) }

        assertContains(failure.message.orEmpty(), "knitty.lock")
        assertFalse(root.resolve("output").exists())
    }

    @Test
    fun checksumAndEveryZipPathAreCheckedBeforeExtraction() = stagingDirectory(projectRoot()) { root ->
        val archive = root.resolve("runtime.zip")
        val archives = ReleaseArchives()
        zipFixture(archive, "jre/bin/java.exe", "jre/legal/LICENSE")

        assertFailsWith<IllegalArgumentException> {
            archives.extractRuntime(
                archive,
                root.resolve("bad"),
                "0".repeat(64),
            )
        }
        assertFalse(root.resolve("bad").exists())

        archives.extractRuntime(archive, root.resolve("good"), sha256(archive))
        assertEquals("fixture", root.resolve("good/legal/LICENSE").readText())

        for (unsafe in listOf("jre/../../escape", "/absolute", "jre/C:escape", "jre/..\\escape")) {
            zipFixture(archive, "jre/bin/java.exe", unsafe)
            val failure = assertFailsWith<IllegalArgumentException> {
                archives.extractRuntime(archive, root.resolve("unsafe"), sha256(archive))
            }

            assertContains(failure.message.orEmpty(), "Unsafe")
            assertFalse(root.resolve("unsafe").exists())
        }
    }
}
