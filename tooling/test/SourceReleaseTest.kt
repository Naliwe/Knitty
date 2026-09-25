package knitty.tooling

import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import java.util.zip.GZIPInputStream
import kotlin.io.path.*
import kotlin.test.*

class SourceReleaseTest {
    @Test
    fun sourcesContainTheCommittedRevisionWithoutLocalFilesOrOwnerIdentity() = stagingDirectory(projectRoot()) { root ->
        root.resolve(".gitignore").writeText(".env\nbuild/\ndist/\n")
        root.resolve("kotlin").writeText("committed wrapper")
        root.resolve("module.yaml").writeText("product: jvm/app\n")
        runProcess(listOf("git", "init", "--quiet", "-b", "main"), root)
        runProcess(listOf("git", "config", "--local", "core.autocrlf", "false"), root)
        runProcess(listOf("git", "add", ".gitignore", "kotlin", "module.yaml"), root)
        runProcess(listOf("git", "update-index", "--chmod=+x", "kotlin"), root)
        runProcess(
            listOf(
                "git",
                "-c",
                "user.name=Fixture",
                "-c",
                "user.email=fixture@example.com",
                "commit",
                "--quiet",
                "-m",
                "Fixture",
            ),
            root,
        )

        root.resolve(".env").writeText("fixture secret")
        root.resolve("untracked.txt").writeText("local notes")
        root.resolve("kotlin").writeText("uncommitted change")

        val artifact = SourceRelease(root).assemble("0.7.0")
        val files = mutableMapOf<String, String>()
        TarArchiveInputStream(GZIPInputStream(artifact.inputStream())).use { archive ->
            while (true) {
                val entry = archive.nextEntry ?: break
                assertEquals(0L, entry.longUserId)
                assertEquals(0L, entry.longGroupId)
                assertTrue(entry.userName in setOf("", "root"))
                assertTrue(entry.groupName in setOf("", "root"))
                if (entry.isDirectory) continue

                files[entry.name] = archive.readBytes().decodeToString()
                if (entry.name.endsWith("/kotlin")) assertEquals(493, entry.mode and 511)
            }
        }

        assertEquals(setOf(".gitignore", "kotlin", "module.yaml").map { "knitty/$it" }.toSet(), files.keys)
        assertEquals("committed wrapper", files["knitty/kotlin"])
        assertEquals(
            "${sha256(artifact)}  ${artifact.name}\n",
            artifact.resolveSibling("${artifact.name}.sha256").readText(),
        )
    }
}
