package knitty.tooling

import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import java.io.IOException
import java.nio.file.Path
import java.util.zip.GZIPInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.*
import kotlin.test.*

class ReleaseTest {
    @Test
    fun publicArchiveIsAllowlistedAndHasAnonymousMetadataAndExactHash() = fixture { root ->
        root.resolve(".env").writeText("private")
        root.resolve("knitty.lock").writeText("private profile")
        val jar = root.resolve("app.jar").apply { writeText("fixture jar") }

        val artifact = PublicRelease(root).assemble("0.7.0", "jvm", jar = jar)
        val names = mutableSetOf<String>()
        TarArchiveInputStream(GZIPInputStream(artifact.inputStream())).use { archive ->
            while (true) {
                val entry = archive.nextEntry ?: break
                names += entry.name
                assertEquals(0L, entry.longUserId)
                assertEquals(0L, entry.longGroupId)
                assertEquals("", entry.userName)
                assertEquals("", entry.groupName)
                assertEquals(if (entry.name.endsWith("/knitty")) 493 else 420, entry.mode)
            }
        }

        val expected = setOf("LICENSE", "THIRD-PARTY-NOTICES.md", "README.md", "knitty.jar", "knitty")
            .map { "knitty-0.7.0/$it" }
            .toSet()
        assertEquals(expected, names)
        assertEquals(
            "${sha256(artifact)}  ${artifact.name}\n",
            artifact.resolveSibling("${artifact.name}.sha256").readText(),
        )
    }

    @Test
    fun manifestsUseFinalArtifactHashesAndAllowIndependentAurGeneration() = fixture { root ->
        val jvm = root.resolve("knitty-0.7.0-jvm.tar.gz").apply { writeText("fixture archive") }
        val source = root.resolve("knitty-0.7.0-source.tar.gz").apply { writeText("fixture source") }
        val windows = root.resolve("knitty-0.7.0-windows-x64.zip")
        zipFixture(windows, "knitty/knitty.exe", "knitty/app/knitty.jar", "knitty/runtime/release")
        val manifests = ReleaseManifests(root)

        val (aur, winget) = manifests.generate(
            "0.7.0",
            artifacts = root,
            output = root.resolve("output"),
            date = "2026-09-25",
        )
        val recipes = aur.associateBy { it.name }
        assertEquals(setOf("knitty", "knitty-bin"), recipes.keys)
        val binaryRecipe = recipes.getValue("knitty-bin").resolve("PKGBUILD").readText()
        val sourceRecipe = recipes.getValue("knitty").resolve("PKGBUILD").readText()
        val installer = assertNotNull(winget).resolve("Naliwe.Knitty.installer.yaml").readText()

        assertContains(binaryRecipe, sha256(jvm))
        assertContains(binaryRecipe, $$"""provides=("knitty=$pkgver")""")
        assertContains(binaryRecipe, "conflicts=('knitty')")
        assertContains(sourceRecipe, sha256(source))
        assertContains(sourceRecipe, "conflicts=('knitty-bin')")
        assertContains(installer, sha256(windows).uppercase())
        assertContains(installer, "knitty/knitty.exe")
        assertEquals(3, winget.listDirectoryEntries("*.yaml").size)

        if (!isWindows && System.getenv("PATH").split(':').any { Path(it).resolve("makepkg").isExecutable() }) {
            for (directory in aur) {
                manifests.sourceInfo(directory)
                val metadata = directory.resolve(".SRCINFO").readText()
                assertContains(metadata, "depends = java-runtime>=21")
                assertContains(metadata, "pkgver = 0.7.0")
                assertContains(metadata, "pkgname = ${directory.name}")
            }
            val sourceMetadata = recipes.getValue("knitty").resolve(".SRCINFO").readText()
            assertContains(sourceMetadata, "makedepends = jdk21-openjdk")
            assertContains(sourceMetadata, "sha256sums = ${sha256(source)}")
        }

        windows.deleteExisting()
        val aurOnly = manifests.generate("0.7.0", artifacts = root, output = root.resolve("aur-only"), target = "aur")
        assertNull(aurOnly.winget)
        assertEquals(listOf("knitty", "knitty-bin"), aurOnly.aur.map { it.name })
        assertEquals(binaryRecipe, aurOnly.aur.last().resolve("PKGBUILD").readText())
        assertEquals(sourceRecipe, aurOnly.aur.first().resolve("PKGBUILD").readText())

        source.deleteExisting()
        assertFailsWith<IOException> {
            manifests.generate("0.7.0", artifacts = root, output = root.resolve("missing-source"), target = "aur")
        }
        assertFalse(root.resolve("missing-source").exists())
    }

    @Test
    fun unsafeVersionsAndUnresolvedTemplatesAndMissingLicenseAreRejected() = fixture { root ->
        for (version in listOf("../escape", "1.0.0;id", "1.0.0-rc1", "v1.0.0")) {
            assertFailsWith<IllegalArgumentException> { releaseVersion(version) }
        }
        assertFailsWith<IllegalArgumentException> { renderTemplate("@MISSING@", emptyMap()) }

        val jar = root.resolve("app.jar").apply { writeText("fixture") }
        root.resolve("LICENSE").deleteExisting()
        val failure =
            assertFailsWith<IllegalArgumentException> { PublicRelease(root).assemble("0.7.0", "jvm", jar = jar) }
        assertContains(failure.message.orEmpty(), "LICENSE")
        assertFalse(root.resolve("dist/knitty-0.7.0-jvm.tar.gz").exists())
    }

    private fun fixture(action: (Path) -> Unit) = stagingDirectory(projectRoot()) { root ->
        root.resolve("LICENSE").writeText("fixture license")
        root.resolve("THIRD-PARTY-NOTICES.md").writeText("fixture third-party notices")
        val templates = projectRoot().resolve("packaging")
        for (name in listOf(
            "README.md", "aur/knitty", "aur/knitty.PKGBUILD.in", "aur/knitty-bin.PKGBUILD.in",
            "winget/Naliwe.Knitty.yaml.in", "winget/Naliwe.Knitty.installer.yaml.in",
            "winget/Naliwe.Knitty.locale.en-US.yaml.in",
        )) {
            val target = root.resolve("packaging/$name")
            target.parent.createDirectories()
            templates.resolve(name).copyTo(target)
        }
        action(root)
    }
}

internal fun zipFixture(path: Path, vararg names: String) {
    ZipOutputStream(path.outputStream()).use { archive ->
        for (name in names) {
            archive.putNextEntry(ZipEntry(name))
            archive.write("fixture".encodeToByteArray())
            archive.closeEntry()
        }
    }
}
