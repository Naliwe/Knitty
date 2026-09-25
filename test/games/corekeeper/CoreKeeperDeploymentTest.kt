package knitty.games.corekeeper

import knitty.core.model.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.*
import kotlin.test.*

class CoreKeeperDeploymentTest {
    @Test
    fun absentOptionalNativeDependenciesDoNotBlockInstallation() = runTest {
        fixture { installation ->
            val optional = manifest.replace(
                "\"dependencies\":[]",
                "\"dependencies\":[{\"modName\":\"OptionalMod\",\"required\":false}]",
            )
            val bytes = archive(manifestText = optional)
            val result = CoreKeeperDeployment().install(installation, change(bytes)) { sink ->
                sink.write(bytes, bytes.size)
                DownloadOutcome.Downloaded
            }
            assertIs<ApplyOutcome.Installed>(result)
        }
    }

    @Test
    fun installsRootAndWrappedArchivesWithOwnershipInOneCommit() = runTest {
        for (prefix in listOf("", "Example/")) {
            fixture { installation ->
                val archive = archive(prefix)
                val change = change(archive)
                val result = CoreKeeperDeployment().install(installation, change) { sink ->
                    sink.write(archive, archive.size)
                    DownloadOutcome.Downloaded
                }
                val target = Path(assertIs<ApplyOutcome.Installed>(result).directory)
                assertEquals("mod data", target.resolve("Scripts/mod.cs").readText())
                val receipt = Json.decodeFromString<OwnershipReceipt>(target.resolve(receiptName).readText())
                assertEquals("modio", receipt.provider)
                assertEquals("456", receipt.artifactId)
                assertEquals(setOf("ModManifest.json", "Scripts/mod.cs"), receipt.files.keys)
                assertEquals(64, receipt.files.getValue("Scripts/mod.cs").length)
                assertFalse(target.resolve(receiptName).readText().contains(installation.directory))
                assertNoStaging(installation)

                val repeated = CoreKeeperDeployment().install(installation, change) { error("Must not download twice") }
                assertEquals(ApplyOutcome.Failed(ApplyFailure.DeploymentConflict), repeated)
                assertEquals("mod data", target.resolve("Scripts/mod.cs").readText())
            }
        }
    }

    @Test
    fun checksumSizeAndDownloadFailuresNeverPublish() = runTest {
        for (failure in listOf(
            ApplyFailure.ChecksumMismatch,
            ApplyFailure.ArtifactChanged,
            ApplyFailure.DownloadFailed,
        )) {
            fixture { installation ->
                val archive = archive()
                val original = change(archive)
                val change = when (failure) {
                    ApplyFailure.ChecksumMismatch -> original.copy(
                        artifact = original.artifact.copy(
                            checksum = Checksum(ChecksumAlgorithm.MD5, "0".repeat(32)),
                        ),
                    )

                    ApplyFailure.ArtifactChanged -> original.copy(artifact = original.artifact.copy(sizeBytes = archive.size + 1L))
                    else -> original
                }
                val result = CoreKeeperDeployment().install(installation, change) { sink ->
                    sink.write(archive, archive.size)
                    if (failure == ApplyFailure.DownloadFailed) DownloadOutcome.Failed(failure) else DownloadOutcome.Downloaded
                }
                assertEquals(ApplyOutcome.Failed(failure), result)
                assertNoMods(installation)
                assertNoStaging(installation)
            }
        }
    }

    @Test
    fun rejectsTraversalAbsolutePathsCaseAliasesAndMissingManifest() = runTest {
        val unsafe = listOf("../escape", "/tmp/escape", "C:/escape", "folder\\escape", ".knitty-ownership.json")
        val archives = unsafe.map { archive(extra = mapOf(it to "bad")) } + listOf(
            archive(extra = mapOf("MODMANIFEST.JSON" to "{}")),
            archive(extra = mapOf("scripts/other.cs" to "bad")),
            archive(extra = mapOf("Scripts/mod.cs/nested" to "bad")),
            zip(mapOf("mod.dll" to "binary")),
            archive().dropLast(22).toByteArray(),
            archive(extra = mapOf("other/ModManifest.json" to manifest)),
            archive(extra = mapOf("Scripts/../escape" to "bad")),
        )
        for (archive in archives) {
            fixture { installation ->
                val result = CoreKeeperDeployment().install(installation, change(archive)) { sink ->
                    sink.write(archive, archive.size)
                    DownloadOutcome.Downloaded
                }
                assertEquals(ApplyOutcome.Failed(ApplyFailure.InvalidArchive), result)
                assertNoMods(installation)
                assertNoStaging(installation)
            }
        }
    }

    @Test
    fun manifestDependenciesAndMissingDeclaredFilesCannotBeInstalled() = runTest {
        for ((manifest, failure) in listOf(
            manifest.replace(
                "\"dependencies\":[]",
                "\"dependencies\":[{\"modName\":\"dependency\",\"required\":true}]",
            ) to ApplyFailure.MissingDependency("Example", "dependency"),
            manifest.replace("Scripts/mod.cs", "Scripts/missing.cs") to ApplyFailure.InvalidArchive,
        )) {
            fixture { installation ->
                val archive = archive(manifestText = manifest)
                val result = CoreKeeperDeployment().install(installation, change(archive)) { sink ->
                    sink.write(archive, archive.size)
                    DownloadOutcome.Downloaded
                }
                assertEquals(ApplyOutcome.Failed(failure), result)
                assertNoMods(installation)
            }
        }
    }

    @Test
    fun duplicateNativeIdentityAndSymlinkModsAreNeverOverwritten() = runTest {
        fixture { installation ->
            val existing = Path(installation.modsDirectory).resolve("manual").createDirectories()
            existing.resolve("ModManifest.json").writeText(manifest)
            existing.resolve("user.txt").writeText("keep")
            val archive = archive()
            val result = CoreKeeperDeployment().install(installation, change(archive)) { sink ->
                sink.write(archive, archive.size)
                DownloadOutcome.Downloaded
            }
            assertEquals(ApplyOutcome.Failed(ApplyFailure.DeploymentConflict), result)
            assertEquals(listOf(existing), Path(installation.modsDirectory).listDirectoryEntries())
            assertEquals("keep", existing.resolve("user.txt").readText())
        }
        fixture { installation ->
            val elsewhere = Path(installation.directory).resolve("elsewhere").createDirectory()
            Files.createSymbolicLink(Path(installation.modsDirectory), elsewhere)
            val archive = archive()
            val result = CoreKeeperDeployment().install(installation, change(archive)) { error("Must not download") }
            assertEquals(ApplyOutcome.Failed(ApplyFailure.InstallationNotFound), result)
            assertTrue(elsewhere.listDirectoryEntries().isEmpty())
        }
    }

    @Test
    fun commitFailureLeavesExistingModsIntactAndCleansStaging() = runTest {
        fixture { installation ->
            val existing = Path(installation.modsDirectory).resolve("keep").createDirectories()
            existing.resolve("user.txt").writeText("keep")
            val archive = archive()
            val deployment = CoreKeeperDeployment { _, _ -> throw IOException("disk failure") }
            val result = deployment.install(installation, change(archive)) { sink ->
                sink.write(archive, archive.size)
                DownloadOutcome.Downloaded
            }
            assertEquals(ApplyOutcome.Failed(ApplyFailure.FilesystemFailure), result)
            assertEquals(listOf(existing), Path(installation.modsDirectory).listDirectoryEntries())
            assertEquals("keep", existing.resolve("user.txt").readText())
            assertNoStaging(installation)
        }
    }

    @Test
    fun cancellationCleansStagingAndConcurrentInstallIsRejected() = runTest {
        fixture { installation ->
            val started = CompletableDeferred<Unit>()
            val archive = archive()
            val change = change(archive)
            val first = async {
                CoreKeeperDeployment().install(installation, change) { sink ->
                    sink.write(archive, 10)
                    started.complete(Unit)
                    awaitCancellation()
                }
            }
            started.await()
            val second =
                CoreKeeperDeployment().install(installation, change) { error("Must not download concurrently") }
            assertEquals(ApplyOutcome.Failed(ApplyFailure.InstallationBusy), second)
            first.cancelAndJoin()
            assertNoMods(installation)
            assertNoStaging(installation)
        }
    }

    @Test
    fun locatorRequiresGameLayoutAndNeverCreatesFiles() = runTest {
        val root = createTempDirectory("knitty-location-").toRealPath()
        try {
            val locator = CoreKeeperInstallation(home = root)
            assertEquals(
                InstallationOutcome.Failed(ApplyFailure.InstallationNotFound),
                locator.locate(GameId("core-keeper"), root.toString()),
            )
            assertTrue(root.listDirectoryEntries().isEmpty())
            root.resolve("CoreKeeper").writeText("")
            root.resolve("CoreKeeper_Data/StreamingAssets").createDirectories()
            val found = assertIs<InstallationOutcome.Found>(locator.locate(GameId("core-keeper"), root.toString()))
            assertEquals(
                root.resolve("CoreKeeper_Data/StreamingAssets/Mods").toString(),
                found.installation.modsDirectory,
            )
            assertNoMods(found.installation)
        } finally {
            deleteTree(root)
        }
    }
}

private const val manifest =
    """{"guid":"abc123","name":"Example","files":[{"path":"Scripts/mod.cs"}],"dependencies":[]}"""

private fun archive(
    prefix: String = "",
    manifestText: String = manifest,
    extra: Map<String, String> = emptyMap(),
): ByteArray =
    zip(mapOf("${prefix}ModManifest.json" to manifestText, "${prefix}Scripts/mod.cs" to "mod data") + extra)

private fun zip(files: Map<String, String>): ByteArray = ByteArrayOutputStream().apply {
    ZipOutputStream(this).use { zip ->
        for ((name, text) in files) {
            zip.putNextEntry(ZipEntry(name))
            zip.write(text.encodeToByteArray())
            zip.closeEntry()
        }
    }
}.toByteArray()

private fun change(bytes: ByteArray) = Change.Install(
    PackageId("modio", "123"), "Example",
    PackageArtifact(
        PackageVersion(ArtifactId("456"), "1.0"), "mod.zip", bytes.size.toLong(),
        Checksum(ChecksumAlgorithm.MD5, MessageDigest.getInstance("MD5").digest(bytes).toHexString()),
    ),
)

private suspend fun fixture(block: suspend (GameInstallation) -> Unit) {
    val root = withContext(Dispatchers.IO) {
        createTempDirectory("knitty-install-").toRealPath()
    }
    try {
        root.resolve("CoreKeeper").writeText("")
        val mods = root.resolve("CoreKeeper_Data/StreamingAssets").createDirectories().resolve("Mods")
        block(GameInstallation(GameId("core-keeper"), root.toString(), mods.toString()))
    } finally {
        deleteTree(root)
    }
}

private fun assertNoMods(installation: GameInstallation) {
    val mods = Path(installation.modsDirectory)
    assertTrue(!mods.exists() || mods.listDirectoryEntries().isEmpty())
}

private fun assertNoStaging(installation: GameInstallation) {
    val state = Path(installation.modsDirectory).parent.resolve(".knitty")
    assertTrue(!state.exists() || state.listDirectoryEntries().none { it.name.startsWith("staging-") })
}

private fun deleteTree(root: Path) {
    Files.walk(root).use { paths ->
        paths.sorted(Comparator.reverseOrder()).forEach { it.deleteIfExists() }
    }
}
