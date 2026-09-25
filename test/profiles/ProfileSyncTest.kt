package knitty.profiles

import knitty.core.application.DefaultManageProfile
import knitty.core.application.DefaultPlanInstall
import knitty.core.application.ManageProfile
import knitty.core.model.*
import knitty.core.ports.DownloadArtifact
import knitty.core.ports.InstallCandidateOutcome
import knitty.core.ports.ProfileDeployment
import knitty.games.corekeeper.CoreKeeperDeployment
import knitty.games.corekeeper.CoreKeeperInstallation
import knitty.games.corekeeper.CoreKeeperProfileDeployment
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.*
import kotlin.test.*

class ProfileSyncTest {
    @Test
    fun progressCountsWrittenBytesAndNeverReportsCommitAfterAnInterruptedDownload() = runTest {
        for (fail in listOf(false, true)) {
            scenario { fixture ->
                fixture.add("1")
                val bytes = fixture.bytes("1")
                val half = bytes.size / 2
                val service = fixture.service(
                    downloader = DownloadArtifact { _, _, sink ->
                        sink.write(bytes, half)
                        if (fail) DownloadOutcome.Failed(ApplyFailure.DownloadFailed) else {
                            sink.write(bytes.copyOfRange(half, bytes.size), bytes.size - half)
                            DownloadOutcome.Downloaded
                        }
                    },
                )
                val events = mutableListOf<OperationProgress>()
                val result = service.apply(service.planSync(game).value(), events::add)
                val download = events.filter { it.stage == ProgressStage.Downloading }

                assertEquals(
                    if (fail) listOf(0L, half.toLong()) else listOf(0L, half.toLong(), bytes.size.toLong()),
                    download.map { it.completed },
                )
                assertTrue(download.all { it.total == bytes.size.toLong() })
                assertEquals(!fail, events.any { it.stage == ProgressStage.Committing })
                if (fail) assertIs<ProfileResult.Failed>(result) else assertIs<ProfileResult.Success<Unit>>(result)
            }
        }
    }

    @Test
    fun sharedDependenciesAreLockedDeployedAndRemovedOnlyWhenUnneeded() = runTest {
        scenario { fixture ->
            fixture.dependencies = mapOf("1" to listOf("3"), "2" to listOf("3"), "3" to listOf("4"))
            fixture.nativeDependencies = fixture.dependencies
            fixture.add("1")
            fixture.add("2")
            val snapshot = fixture.store.read().value()
            assertEquals(listOf("1", "2"), snapshot.profile!!.packages.map { it.value })
            assertEquals(listOf("4", "3", "1", "2"), snapshot.lock!!.packages.map { it.id.value })

            fixture.service.apply(fixture.service.planSync(game).value()).value()
            assertEquals(4, fixture.downloads)
            val initial = fixture.deployment.inspect(fixture.installation, "test-profile").value()
            assertEquals(setOf("1", "2", "3", "4"), initial.managed.map { it.value }.toSet())

            val removeFirst = fixture.service.remove(game, PackageId("modio", "1")).value()
            assertEquals(listOf("1"), removeFirst.removals.map { it.id.value })
            fixture.service.save(removeFirst).value()
            fixture.service.apply(fixture.service.planSync(game).value()).value()

            val removeLast = fixture.service.remove(game, PackageId("modio", "2")).value()
            assertEquals(setOf("2", "3", "4"), removeLast.removals.map { it.id.value }.toSet())
            fixture.service.save(removeLast).value()
            fixture.service.apply(fixture.service.planSync(game).value()).value()
            assertTrue(fixture.deployment.inspect(fixture.installation, "test-profile").value().packages.isEmpty())
            assertNoStaging(fixture)
        }
    }

    @Test
    fun explicitlyAddedDependencyRemainsAfterItsDependentIsRemoved() = runTest {
        scenario { fixture ->
            fixture.dependencies = mapOf("1" to listOf("2"))
            fixture.add("1")
            val pins = fixture.store.read().value().lock!!.packages
            fixture.providerVersion = 1
            fixture.add("2")
            assertEquals(pins, fixture.store.read().value().lock!!.packages)

            val removal = fixture.service.remove(game, PackageId("modio", "1")).value()
            assertEquals(listOf("2"), removal.lock.packages.map { it.id.value })
            assertEquals(listOf("2"), removal.profile.packages.map { it.value })
        }
    }

    @Test
    fun updateReconcilesChangedDependencyEdgesEvenWhenRootFileIsUnchanged() = runTest {
        scenario { fixture ->
            fixture.dependencies = mapOf("1" to listOf("2"))
            fixture.add("1")
            val before = fixture.store.read().value()
            fixture.dependencies = mapOf("1" to listOf("3"))

            val plan = fixture.service.planUpdate(game).value()

            assertEquals(before, fixture.store.read().value())
            assertEquals(listOf("3"), plan.additions.map { it.id.value })
            assertEquals(listOf("2"), plan.removals.map { it.id.value })
            assertEquals("1", plan.updates.single().replacement.id.value)
            assertEquals(plan.updates.single().current.artifact, plan.updates.single().replacement.artifact)
            fixture.service.save(plan).value()
            fixture.service.apply(fixture.service.planSync(game).value()).value()
            assertEquals(
                setOf("1", "3"),
                fixture.deployment.inspect(fixture.installation, "test-profile").value().managed.map { it.value }
                    .toSet(),
            )
        }
    }

    @Test
    fun failedDependencyDownloadOrMissingNativeRequirementNeverPublishesPartialGraph() = runTest {
        for (missingNative in listOf(false, true)) {
            scenario { fixture ->
                fixture.add("9")
                fixture.service.apply(fixture.service.planSync(game).value()).value()
                val before = fixture.deployment.inspect(fixture.installation, "test-profile").value()
                fixture.dependencies = mapOf("1" to listOf("2"))
                fixture.nativeDependencies = if (missingNative) mapOf("1" to listOf("404")) else fixture.dependencies
                fixture.add("1")
                if (!missingNative) fixture.failDownload = "1"
                val desired = fixture.store.read().value()

                val failure = assertIs<ProfileResult.Failed>(
                    fixture.service.apply(
                        fixture.service.planSync(game).value(),
                    ),
                ).failure

                val expected = if (missingNative) ApplyFailure.MissingDependency(
                    "Mod 1",
                    "Mod 404",
                ) else ApplyFailure.DownloadFailed
                assertEquals(ProfileFailure.Deployment(expected), failure)
                assertEquals(before, fixture.deployment.inspect(fixture.installation, "test-profile").value())
                assertEquals(desired, fixture.store.read().value())
                assertNoStaging(fixture)
            }
        }
    }

    @Test
    fun updatesSaveReviewedPinsThenSyncReplacesOwnedModsWithThoseExactFiles() = runTest {
        scenario { fixture ->
            fixture.add("1")
            fixture.add("2")
            fixture.service.apply(fixture.service.planSync(game).value()).value()
            val before = fixture.deployment.inspect(fixture.installation, "test-profile").value()
            val snapshot = fixture.store.read().value()
            fixture.providerVersion = 1

            val update = fixture.service.planUpdate(game).value()

            assertEquals(2, update.updates.size)
            assertEquals(snapshot, fixture.store.read().value())
            assertEquals(before, fixture.deployment.inspect(fixture.installation, "test-profile").value())
            fixture.service.save(update).value()
            assertEquals(before, fixture.deployment.inspect(fixture.installation, "test-profile").value())

            fixture.providerVersion = 2
            val plan = fixture.service.planSync(game).value()
            assertEquals(listOf("10", "20"), plan.remove.map { it.artifactId.value })
            assertEquals(listOf("11", "21"), plan.install.map { it.artifact.version.artifactId.value })
            fixture.service.apply(plan).value()

            val after = fixture.deployment.inspect(fixture.installation, "test-profile").value()
            assertEquals(setOf("11", "21"), after.packages.map { it.artifactId.value }.toSet())
            assertEquals(update.lock, fixture.store.read().value().lock)
            assertTrue(fixture.service.planSync(game).value().install.isEmpty())
            assertNoStaging(fixture)
        }
    }

    @Test
    fun failedReplacementDownloadsAndPublicationRestoreOldInstallationAndCanBeRetried() = runTest {
        for (commitFailure in listOf(false, true)) {
            scenario { fixture ->
                fixture.add("1")
                fixture.add("2")
                fixture.service.apply(fixture.service.planSync(game).value()).value()
                val before = fixture.deployment.inspect(fixture.installation, "test-profile").value()
                fixture.providerVersion = 1
                fixture.service.save(fixture.service.planUpdate(game).value()).value()
                val desired = fixture.store.read().value()
                val deployment = if (commitFailure) {
                    CoreKeeperProfileDeployment { _, target ->
                        assertFalse(target.exists())
                        assertTrue(target.parent.resolve(".knitty/profile-backup").exists())
                        throw IOException("Cannot publish replacement tree")
                    }
                } else {
                    fixture.failDownload = "2"
                    fixture.deployment
                }
                val service = fixture.service(deployment)

                assertIs<ProfileResult.Failed>(service.apply(service.planSync(game).value()))

                assertEquals(before, fixture.deployment.inspect(fixture.installation, "test-profile").value())
                assertEquals(desired, fixture.store.read().value())
                assertNoStaging(fixture)

                fixture.failDownload = null
                fixture.service.apply(fixture.service.planSync(game).value()).value()
                assertTrue(fixture.service.planSync(game).value().install.isEmpty())
            }
        }
    }

    @Test
    fun updateSaveRejectsProfileChangesAfterReview() = runTest {
        scenario { fixture ->
            fixture.add("1")
            fixture.providerVersion = 1
            val update = fixture.service.planUpdate(game).value()
            fixture.add("2")
            val before = fixture.store.read().value()

            assertEquals(ProfileResult.Failed(ProfileFailure.StalePlan), fixture.service.save(update))
            assertEquals(before, fixture.store.read().value())
        }
    }

    @Test
    fun sharedProfilePinsSurviveProviderChangesAndSyncIsIdempotent() = runTest {
        scenario { source ->
            source.add("1")
            source.add("2")
            assertFalse(Path(source.installation.modsDirectory).exists())
            val publishedLock = source.store.read().value().lock

            scenario { target ->
                target.profileDir.createDirectories()
                for (name in listOf("knitty.yaml", "knitty.lock")) {
                    source.profileDir.resolve(name).copyTo(target.profileDir.resolve(name))
                }
                target.providerVersion = 99
                val plan = target.service.planSync(game).value()
                assertEquals(listOf("10", "20"), plan.install.map { it.artifact.version.artifactId.value })
                assertEquals(0, target.resolutions)
                assertIs<ProfileResult.Success<Unit>>(target.service.apply(plan))
                assertEquals(publishedLock, target.store.read().value().lock)
                assertEquals(2, target.downloads)

                val repeat = target.service.planSync(game).value()
                assertTrue(repeat.install.isEmpty())
                assertEquals(2, repeat.keep.size)
                assertIs<ProfileResult.Success<Unit>>(target.service.apply(repeat))
                assertEquals(2, target.downloads)
                assertEquals(0, target.resolutions)
            }
        }
    }

    @Test
    fun removalOnlyTouchesProfileOwnedModsAndKeepsUnrelatedFiles() = runTest {
        scenario { fixture ->
            val manual = Path(fixture.installation.modsDirectory).resolve("manual").createDirectories()
            manual.resolve("user.cfg").writeText("leave this alone")
            fixture.add("1")
            fixture.add("2")
            val initial = fixture.service.planSync(game).value()
            assertEquals(listOf("manual"), initial.untouched)
            fixture.service.apply(initial).value()
            fixture.service.save(fixture.service.remove(game, PackageId("modio", "1")).value()).value()

            val plan = fixture.service.planSync(game).value()
            assertEquals(listOf(PackageId("modio", "1")), plan.remove.map { it.id })
            fixture.service.apply(plan).value()
            assertEquals("leave this alone", manual.resolve("user.cfg").readText())
            val after = fixture.deployment.inspect(fixture.installation, "test-profile").value()
            assertEquals(listOf(PackageId("modio", "2")), after.packages.map { it.id })
            assertEquals(setOf(PackageId("modio", "2")), after.managed)
        }
    }

    @Test
    fun laterDownloadFailureAndCommitFailureLeaveEntireInstallationUnchanged() = runTest {
        for (commitFailure in listOf(false, true)) {
            scenario { fixture ->
                fixture.add("1")
                fixture.service.apply(fixture.service.planSync(game).value()).value()
                val before = fixture.deployment.inspect(fixture.installation, "test-profile").value()
                fixture.add("2")
                fixture.add("3")
                val deployment =
                    if (commitFailure) CoreKeeperProfileDeployment { _, _ -> throw IOException("commit failed") } else fixture.deployment
                if (!commitFailure) fixture.failDownload = "3"
                val service = fixture.service(deployment)
                val plan = service.planSync(game).value()

                assertIs<ProfileResult.Failed>(service.apply(plan))
                assertEquals(before, fixture.deployment.inspect(fixture.installation, "test-profile").value())
                assertNoStaging(fixture)
            }
        }
    }

    @Test
    fun modifiedOwnedFilesAndStalePlansAreRefused() = runTest {
        scenario { fixture ->
            fixture.add("1")
            fixture.service.apply(fixture.service.planSync(game).value()).value()
            val plan = fixture.service.planSync(game).value()
            val mods = Path(fixture.installation.modsDirectory)
            mods.resolve("new-unmanaged.txt").writeText("keep")
            assertEquals(ProfileResult.Failed(ProfileFailure.StalePlan), fixture.service.apply(plan))
            assertEquals("keep", mods.resolve("new-unmanaged.txt").readText())

            val owned = mods.listDirectoryEntries().single { it.isDirectory() }
            owned.resolve("mod.dll").writeText("edited")
            assertEquals(ProfileResult.Failed(ProfileFailure.ModifiedInstallation), fixture.service.planSync(game))
            assertEquals("edited", owned.resolve("mod.dll").readText())
        }
    }

    @Test
    fun unchangedStandaloneInstallIsExplicitlyAdoptedWithoutDownloading() = runTest {
        scenario { fixture ->
            val item = fixture.item("1")
            val bytes = fixture.bytes("1")
            assertIs<ApplyOutcome.Installed>(
                CoreKeeperDeployment().install(fixture.installation, item.install()) { sink ->
                    sink.write(bytes, bytes.size)
                    DownloadOutcome.Downloaded
                },
            )
            fixture.add("1")
            val plan = fixture.service.planSync(game).value()
            assertTrue(plan.install.isEmpty())
            assertEquals(listOf(PackageId("modio", "1")), plan.adopt.map { it.id })
            fixture.service.apply(plan).value()
            assertEquals(0, fixture.downloads)
            assertEquals(
                setOf(PackageId("modio", "1")),
                fixture.deployment.inspect(fixture.installation, "test-profile").value().managed,
            )
        }
    }

    @Test
    fun cancelDuringStagingReleasesLocksWithoutChangingLiveFiles() = runTest {
        scenario { fixture ->
            fixture.add("1")
            val started = CompletableDeferred<Unit>()
            val service = fixture.service(
                downloader = DownloadArtifact { _, _, _ ->
                    started.complete(Unit)
                    awaitCancellation()
                },
            )
            val plan = service.planSync(game).value()
            val job = async { service.apply(plan) }
            started.await()
            assertEquals(ProfileResult.Failed(ProfileFailure.Busy), fixture.service.apply(plan))
            job.cancelAndJoin()
            assertFalse(Path(fixture.installation.modsDirectory).exists())
            assertNoStaging(fixture)
            fixture.service.apply(fixture.service.planSync(game).value()).value()
        }
    }

    @Test
    fun existingPinsArePreservedOnReAddBySlugAndManualProfileEditsRequireLock() = runTest {
        scenario { fixture ->
            fixture.add("1")
            fixture.providerVersion = 99
            val edit = fixture.service.add(game, PackageReference("modio", "mod-1")).value()
            assertEquals("10", edit.lock.packages.single().artifact.version.artifactId.value)
            fixture.service.save(edit).value()
            val file = fixture.profileDir.resolve("knitty.yaml")
            file.appendText("  - \"modio:2\"\n")
            assertEquals(ProfileResult.Failed(ProfileFailure.LockOutOfDate), fixture.service.planSync(game))
            val resolved = fixture.service.lock(game).value()
            assertEquals(listOf("10", "119"), resolved.lock.packages.map { it.artifact.version.artifactId.value })
        }
    }

    @Test
    fun staleProfileEditsAndForeignInstallationBindingsAreRejected() = runTest {
        scenario { fixture ->
            val first = fixture.service.add(game, PackageReference("modio", "1")).value()
            val stale = fixture.service.add(game, PackageReference("modio", "2")).value()
            fixture.service.save(first).value()
            assertEquals(ProfileResult.Failed(ProfileFailure.StalePlan), fixture.service.save(stale))
            fixture.service.apply(fixture.service.planSync(game).value()).value()
            assertEquals(
                ProfileResult.Failed(ProfileFailure.DifferentProfile),
                fixture.deployment.inspect(fixture.installation, "other-profile"),
            )
        }
    }
}

private val game = GameId("core-keeper")

private class Scenario(root: Path) {
    val profileDir: Path = root.resolve("profile")
    val store = FileProfileStore(profileDir)
    val installation: GameInstallation
    val deployment = CoreKeeperProfileDeployment()
    var providerVersion = 0
    var dependencies: Map<String, List<String>> = emptyMap()
    var nativeDependencies: Map<String, List<String>> = emptyMap()
    var resolutions = 0
    var downloads = 0
    var failDownload: String? = null

    init {
        val gameRoot = root.resolve("game").createDirectories().toRealPath()
        gameRoot.resolve("CoreKeeper").writeText("")
        val assets = gameRoot.resolve("CoreKeeper_Data/StreamingAssets").createDirectories()
        installation = GameInstallation(game, gameRoot.toString(), assets.resolve("Mods").toString())
    }

    val service: ManageProfile get() = service()
    fun service(deployment: ProfileDeployment = this.deployment, downloader: DownloadArtifact? = null): ManageProfile =
        DefaultManageProfile(
            store,
            DefaultPlanInstall(
                { _, reference ->
                    resolutions++
                    val id = reference.value.removePrefix("mod-")
                    val item = item(id)
                    InstallCandidateOutcome.Found(
                        InstallCandidate(
                            item.id,
                            item.name,
                            item.artifact,
                            dependencies[id].orEmpty().map { PackageId("modio", it) },
                        ),
                    )
                },
                "modio",
                mapOf(game to ProviderGameId("corekeeper")),
            ),
            { requested, path ->
                CoreKeeperInstallation().locate(requested, path ?: installation.directory)
            },
            deployment,
            downloader ?: DownloadArtifact { _, change, sink ->
                downloads++
                if (change.id.value == failDownload) {
                    DownloadOutcome.Failed(ApplyFailure.DownloadFailed)
                } else {
                    val version = change.artifact.version.artifactId.value.toInt() - change.id.value.toInt() * 10
                    val bytes = bytes(change.id.value, version)
                    sink.write(bytes, bytes.size)
                    DownloadOutcome.Downloaded
                }
            },
            mapOf(game to ProviderGameId("corekeeper")),
            newProfileId = { "test-profile" },
        )

    suspend fun add(id: String) {
        service.save(service.add(game, PackageReference("modio", id)).value()).value()
    }

    fun item(id: String): LockedPackage {
        val bytes = bytes(id, providerVersion)
        val fileId = ArtifactId((id.toInt() * 10 + providerVersion).toString())
        val checksum = MessageDigest.getInstance("MD5").digest(bytes).toHexString()
        val artifact = PackageArtifact(
            version = PackageVersion(fileId, "1.0"),
            filename = "mod.zip",
            sizeBytes = bytes.size.toLong(),
            checksum = Checksum(ChecksumAlgorithm.MD5, checksum),
        )
        return LockedPackage(PackageId("modio", id), "Mod $id", artifact)
    }

    fun bytes(id: String, version: Int = 0): ByteArray = ByteArrayOutputStream().apply {
        val required =
            nativeDependencies[id].orEmpty().joinToString(",") { """{"modName":"Mod $it","required":true}""" }
        ZipOutputStream(this).use { zip ->
            for ((name, text) in mapOf(
                "ModManifest.json" to """{"guid":"guid-$id","name":"Mod $id","files":[{"path":"mod.dll"}],"dependencies":[$required]}""",
                "mod.dll" to "binary-$id-$version",
            )) {
                val entry = ZipEntry(name).apply { time = 0 }
                zip.putNextEntry(entry)
                zip.write(text.encodeToByteArray())
                zip.closeEntry()
            }
        }
    }.toByteArray()
}

private suspend fun scenario(block: suspend (Scenario) -> Unit) {
    val root = createTempDirectory("knitty-profile-")
    try {
        block(Scenario(root))
    } finally {
        withContext(Dispatchers.IO) {
            Files.walk(root).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach { it.deleteIfExists() }
            }
        }
    }
}

private fun <T> ProfileResult<T>.value(): T = assertIs<ProfileResult.Success<T>>(this).value
private fun assertNoStaging(fixture: Scenario) {
    val state = Path(fixture.installation.modsDirectory).parent.resolve(".knitty")
    assertTrue(
        state.listDirectoryEntries().none { it.name.startsWith("profile-staging-") || it.name == "profile-backup" },
    )
}
