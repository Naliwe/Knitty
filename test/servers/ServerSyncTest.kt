package knitty.servers

import knitty.core.application.DefaultManageProfile
import knitty.core.model.*
import knitty.core.ports.ServerControl
import knitty.filesystem.RemoteFile
import knitty.filesystem.RemoteFileType
import knitty.filesystem.RemoteFiles
import knitty.filesystem.deleteLocalTree
import knitty.games.corekeeper.CoreKeeperInstallation
import knitty.games.corekeeper.CoreKeeperProfileDeployment
import knitty.profiles.FileProfileStore
import knitty.profiles.ProfileCodec
import knitty.sampleServer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.*
import kotlin.test.*

class ServerSyncTest {
    @Test
    fun remoteInstallUpdateRemoveAndNoopPreserveManualFilesAndRestorePriorPowerState() = runTest {
        scenario { fixture ->
            val manual = fixture.mods.resolve("manual/config.ini")
            manual.parent.createDirectories()
            manual.writeText("keep me")
            fixture.files.modes["/$modsPath/manual/config.ini"] = 384
            val profileText = fixture.profile.resolve("knitty.yaml").readText()
            val plan = fixture.plan()
            assertEquals(listOf("manual"), plan.untouched)
            assertTrue(fixture.events.isEmpty(), "Planning must not mutate or stop the server")

            val progress = mutableListOf<OperationProgress>()
            fixture.service.apply(plan, progress::add).value()

            val stages = progress.map { it.stage }
            assertTrue(stages.indexOf(ProgressStage.Uploading) < stages.indexOf(ProgressStage.StoppingServer))
            assertTrue(stages.indexOf(ProgressStage.StoppingServer) < stages.indexOf(ProgressStage.Committing))
            assertEquals(ProgressStage.StartingServer, stages.last())
            for (stage in listOf(ProgressStage.Downloading, ProgressStage.Uploading, ProgressStage.Verifying)) {
                assertTrue(progress.any { it.stage == stage && it.total != null && it.total > 0 && it.completed == it.total })
            }

            assertEquals(listOf("download", "stop", "publish", "start"), fixture.events)
            assertEquals("keep me", manual.readText())
            assertEquals(384, fixture.files.modes["/$modsPath/manual/config.ini"])
            assertFalse(fixture.transaction.exists())
            assertEquals(profileText, fixture.profile.resolve("knitty.yaml").readText())

            fixture.events.clear()
            fixture.service.apply(fixture.plan()).value()
            assertTrue(fixture.events.isEmpty(), "A no-op sync must not restart the server")

            fixture.version = 2
            fixture.writeProfile()
            val update = fixture.plan()
            assertEquals(1, update.remove.size)
            fixture.service.apply(update).value()
            assertEquals(ArtifactId("2"), fixture.plan().keep.single().artifactId)

            fixture.status = ServerStatus(ServerPowerState.Offline, false)
            fixture.events.clear()
            fixture.writeProfile(empty = true)
            fixture.service.apply(fixture.plan()).value()
            assertEquals(listOf("publish"), fixture.events)
            assertTrue(fixture.plan().keep.isEmpty())
            assertEquals("keep me", manual.readText())
        }
    }

    @Test
    fun failedDownloadAndUploadNeverStopOrChangeTheLiveInstallation() = runTest {
        for (duringUpload in listOf(false, true)) {
            scenario { fixture ->
                fixture.mods.resolve("manual.txt").writeText("original")
                val plan = fixture.plan()
                if (duringUpload) fixture.files.failUpload = true else fixture.failDownload = true

                assertIs<ProfileResult.Failed>(fixture.service.apply(plan))

                assertEquals("original", fixture.mods.resolve("manual.txt").readText())
                assertFalse("stop" in fixture.events)
                assertFalse(fixture.transaction.exists())
            }
        }
    }

    @Test
    fun filesOrPowerChangedAfterReviewRejectThePlan() = runTest {
        for (changePower in listOf(false, true)) {
            scenario { fixture ->
                val plan = fixture.plan()
                if (changePower) fixture.status = ServerStatus(ServerPowerState.Offline, false)
                else fixture.mods.resolve("external.txt").writeText("changed")

                assertEquals(ProfileResult.Failed(ProfileFailure.StalePlan), fixture.service.apply(plan))
                assertFalse("stop" in fixture.events)
                assertFalse("publish" in fixture.events)
            }
        }
    }

    @Test
    fun shutdownFileChangesOrAnExternalRestartPreventPublication() = runTest {
        for (restart in listOf(false, true)) {
            scenario { fixture ->
                val plan = fixture.plan()
                fixture.afterStop = {
                    if (restart) fixture.status = fixture.status.copy(power = ServerPowerState.Running)
                    else fixture.mods.resolve("shutdown.cfg").writeText("saved on shutdown")
                }

                assertEquals(
                    ProfileResult.Failed(ProfileFailure.ServerStopped(ProfileFailure.StalePlan)),
                    fixture.service.apply(plan),
                )
                assertFalse("publish" in fixture.events)
                assertFalse("start" in fixture.events)
            }
        }
    }

    @Test
    fun failedPublicationRestoresOriginalTreeAndLeavesServerStopped() = runTest {
        scenario { fixture ->
            fixture.mods.resolve("manual.txt").writeText("original")
            val plan = fixture.plan()
            fixture.files.failPublish = true

            val result = assertIs<ProfileResult.Failed>(fixture.service.apply(plan))

            assertIs<ProfileFailure.ServerStopped>(result.failure)
            assertEquals("original", fixture.mods.resolve("manual.txt").readText())
            assertFalse("start" in fixture.events)
            assertEquals(ServerPowerState.Offline, fixture.status.power)
        }
    }

    @Test
    fun lostCommitAcknowledgementRetainsJournalAndBackupAndBlocksAnotherSync() = runTest {
        scenario { fixture ->
            fixture.mods.resolve("manual.txt").writeText("original")
            val plan = fixture.plan()
            fixture.files.losePublishReply = true

            assertIs<ProfileResult.Failed>(fixture.service.apply(plan))

            assertTrue(fixture.transaction.resolve("transaction.json").exists())
            assertEquals("original", fixture.transaction.resolve("backup/manual.txt").readText())
            assertFalse("start" in fixture.events)
            assertEquals(
                ProfileResult.Failed(ProfileFailure.Server(ServerFailure.RecoveryRequired)),
                fixture.service.planSync(game, target = "ovh"),
            )
        }
    }

    @Test
    fun stopFailureDoesNotPublishAndRestartFailureReportsCommittedState() = runTest {
        scenario { fixture ->
            fixture.failStop = true
            assertIs<ProfileResult.Failed>(fixture.service.apply(fixture.plan()))
            assertFalse("publish" in fixture.events)
            assertFalse("start" in fixture.events)

            fixture.failStop = false
            fixture.failStart = true
            assertEquals(
                ProfileResult.Failed(ProfileFailure.Server(ServerFailure.RestartFailed)),
                fixture.service.apply(fixture.plan()),
            )
            assertEquals(1, fixture.plan().keep.size)
        }
    }

    @Test
    fun cancellationBeforeCommitPropagatesAndDoesNotPublish() = runTest {
        scenario { fixture ->
            fixture.cancelDownload = true
            assertFailsWith<CancellationException> { fixture.service.apply(fixture.plan()) }
            assertFalse("stop" in fixture.events)
            assertFalse("publish" in fixture.events)
        }
    }

    @Test
    fun symlinksUnknownTargetsAndMixedLocalRemoteSelectionAreRejected() = runTest {
        scenario { fixture ->
            assertEquals(
                ProfileResult.Failed(ProfileFailure.Server(ServerFailure.UnknownTarget)),
                fixture.service.planSync(game, target = "missing"),
            )
            assertEquals(
                ProfileResult.Failed(ProfileFailure.Server(ServerFailure.InvalidTarget)),
                fixture.service.planSync(game, "/local", "ovh"),
            )
            Files.createSymbolicLink(fixture.mods.resolve("escape"), fixture.profile)
            assertEquals(
                ProfileResult.Failed(ProfileFailure.Server(ServerFailure.InvalidTarget)),
                fixture.service.planSync(game, target = "ovh"),
            )
        }
    }
}

private val game = GameId("core-keeper")
private const val modsPath = "CoreKeeperServer_Data/StreamingAssets/Mods"

private class ServerFixture(root: Path) {
    val server: Path = root.resolve("server").createDirectories()
    val profile: Path = root.resolve("profile").createDirectories()
    val mods: Path = server.resolve(modsPath).createDirectories()
    val transaction: Path = mods.parent.resolve(".knitty-server-sync")
    val events = mutableListOf<String>()
    val files = DiskRemoteFiles(server, events)
    var status = ServerStatus(ServerPowerState.Running, false)
    var version = 1
    var failDownload = false
    var cancelDownload = false
    var failStop = false
    var failStart = false
    var afterStop: () -> Unit = { }

    init {
        server.resolve("CoreKeeperServer").writeText("")
        writeProfile()
    }

    val service = DefaultManageProfile(
        store = FileProfileStore(profile),
        planner = { error("Sync must use locked artifacts") },
        locate = { _, _ -> error("Remote sync must not discover a local Steam library") },
        deployment = CoreKeeperProfileDeployment(),
        download = { _, _, sink ->
            events += "download"
            if (cancelDownload) throw CancellationException("cancelled")
            if (failDownload) DownloadOutcome.Failed(ApplyFailure.DownloadFailed)
            else {
                val bytes = archive(version)
                sink.write(bytes, bytes.size)
                DownloadOutcome.Downloaded
            }
        },
        games = mapOf(game to ProviderGameId("corekeeper")),
        newProfileId = { error("Existing profile") },
        serverDeployment = SftpProfileDeployment(
            { files },
            mapOf(game to ServerLayout("CoreKeeperServer", modsPath)),
            CoreKeeperInstallation(),
            CoreKeeperProfileDeployment(),
        ),
        serverControl = object : ServerControl {
            override suspend fun inspect(target: ServerTarget): ProfileResult<ServerStatus> =
                ProfileResult.Success(status)

            override suspend fun stop(target: ServerTarget): ProfileResult<Unit> {
                events += "stop"
                if (failStop) return ProfileResult.Failed(ProfileFailure.Server(ServerFailure.PowerTimeout))
                status = status.copy(power = ServerPowerState.Offline)
                afterStop()
                return ProfileResult.Success(Unit)
            }

            override suspend fun start(target: ServerTarget): ProfileResult<Unit> {
                events += "start"
                if (failStart) return ProfileResult.Failed(ProfileFailure.Server(ServerFailure.ConnectionFailed))
                status = status.copy(power = ServerPowerState.Running)
                return ProfileResult.Success(Unit)
            }
        },
    )

    suspend fun plan(): SyncPlan = service.planSync(game, target = "ovh").value()

    fun writeProfile(empty: Boolean = false) {
        val item = LockedPackage(
            PackageId("modio", "1"), "Example",
            PackageArtifact(
                PackageVersion(ArtifactId("$version"), "$version"),
                "mod.zip",
                archive(version).size.toLong(),
                null,
            ),
        )
        val packages = if (empty) emptyList() else listOf(item)
        val desired = Profile("friends", game, packages.map { it.id }, listOf(sampleServer))
        val codec = ProfileCodec()
        profile.resolve("knitty.yaml").writeText(codec.encodeProfile(desired))
        profile.resolve("knitty.lock").writeText(codec.encodeLock(ProfileLock(desired.id, game, packages)))
    }
}

private class DiskRemoteFiles(private val root: Path, private val events: MutableList<String>) : RemoteFiles {
    var failUpload = false
    var failPublish = false
    var losePublishReply = false
    val modes = mutableMapOf<String, Int>()

    private fun path(remote: String): Path = root.resolve(remote.removePrefix("/"))

    override suspend fun stat(path: String): RemoteFile? {
        val local = path(path)
        if (!local.exists(NOFOLLOW_LINKS)) return null
        val type = when {
            local.isSymbolicLink() -> RemoteFileType.Unsupported
            local.isDirectory() -> RemoteFileType.Directory
            else -> RemoteFileType.File
        }
        return RemoteFile(
            local.name, type, if (type == RemoteFileType.File) local.fileSize() else 0,
            modes[path] ?: if (type == RemoteFileType.Directory) 493 else 420,
        )
    }

    override suspend fun list(path: String): List<RemoteFile> =
        path(path).listDirectoryEntries().map { stat("$path/${it.name}")!! }

    override suspend fun download(path: String, destination: Path, maximumBytes: Long, progress: (Long) -> Unit) {
        assertTrue(path(path).fileSize() <= maximumBytes)
        path(path).copyTo(destination)
        progress(destination.fileSize())
    }

    override suspend fun upload(source: Path, destination: String, progress: (Long) -> Unit) {
        if (failUpload) throw IOException("upload failed")
        source.copyTo(path(destination))
        progress(source.fileSize())
    }

    override suspend fun mkdir(path: String) {
        path(path).createDirectory()
    }

    override suspend fun removeFile(path: String) {
        path(path).deleteExisting()
    }

    override suspend fun removeDirectory(path: String) {
        path(path).deleteExisting()
    }

    override suspend fun chmod(path: String, permissions: Int) {
        modes[path] = permissions
    }

    override fun close() = Unit

    override suspend fun rename(source: String, destination: String) {
        val publish = source.endsWith("/staged")
        if (publish && failPublish) throw IOException("rename failed")
        path(source).moveTo(path(destination))
        for ((key, mode) in modes.toMap()) {
            if (key == source || key.startsWith("$source/")) {
                modes.remove(key)
                modes[destination + key.removePrefix(source)] = mode
            }
        }
        if (publish) {
            events += "publish"
            if (losePublishReply) throw IOException("reply lost")
        }
    }
}

private fun archive(version: Int): ByteArray = ByteArrayOutputStream().apply {
    ZipOutputStream(this).use { zip ->
        for ((name, text) in mapOf(
            "ModManifest.json" to """{"guid":"example","name":"Example","files":[{"path":"mod.dll"}],"dependencies":[]}""",
            "mod.dll" to "binary-$version",
        )) {
            zip.putNextEntry(ZipEntry(name).apply { time = 0 })
            zip.write(text.encodeToByteArray())
            zip.closeEntry()
        }
    }
}.toByteArray()

private suspend fun scenario(action: suspend (ServerFixture) -> Unit) {
    val root = createTempDirectory("knitty-server-test-")
    try {
        action(ServerFixture(root))
    } finally {
        deleteLocalTree(root)
    }
}

private fun <T> ProfileResult<T>.value(): T = assertIs<ProfileResult.Success<T>>(this).value
