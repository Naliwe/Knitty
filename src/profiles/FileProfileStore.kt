package knitty.profiles

import knitty.core.model.*
import knitty.core.ports.ProfileStore
import knitty.filesystem.withExclusiveFileLock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.security.MessageDigest
import kotlin.io.path.*

class FileProfileStore(
    private val directory: Path,
    private val replaceFile: (Path, Path) -> Unit = { source, target ->
        Files.move(
            source,
            target,
            ATOMIC_MOVE,
            REPLACE_EXISTING,
        )
    },
) : ProfileStore {
    private val codec = ProfileCodec()
    private val profileFile = directory.resolve("knitty.yaml")
    private val lockFile = directory.resolve("knitty.lock")
    private val pending = directory.resolve(".knitty-profile-pending")

    override suspend fun read(): ProfileResult<ProfileSnapshot> = withContext(Dispatchers.IO) {
        guarded {
            if (pending.exists(NOFOLLOW_LINKS)) profileFailure(ProfileFailure.RecoveryRequired)
            readSnapshot()
        }
    }

    override suspend fun save(plan: ProfileEditPlan): ProfileResult<Unit> = locked {
        if (readSnapshot() != plan.before) profileFailure(ProfileFailure.StalePlan)

        val profile = codec.encodeProfile(plan.profile)
        val lock = codec.encodeLock(plan.lock)
        if (codec.decodeProfile(profile) != plan.profile || codec.decodeLock(lock) != plan.lock ||
            !plan.lock.isCompleteFor(plan.profile)
        ) {
            profileFailure(ProfileFailure.InvalidLock)
        }

        val oldProfile = readFile(profileFile)
        val oldLock = readFile(lockFile)
        val backups = Files.createTempDirectory(directory, ".knitty-profile-stage-")

        try {
            oldProfile?.let { backups.resolve("knitty.yaml.before").writeText(it) }
            oldLock?.let { backups.resolve("knitty.lock.before").writeText(it) }
            backups.resolve("prepared").writeText("1")
            Files.move(backups, pending, ATOMIC_MOVE)
        } finally {
            if (backups.exists()) {
                backups.listDirectoryEntries().forEach { it.deleteIfExists() }
                backups.deleteIfExists()
            }
        }

        try {
            withContext(NonCancellable) {
                replace(profileFile, profile)
                replace(lockFile, lock)
                replace(pending.resolve("committed"), "1")
            }
        } catch (failure: Exception) {
            withContext(NonCancellable) {
                try {
                    pending.resolve("committed").deleteIfExists()
                    restore(profileFile, oldProfile)
                    restore(lockFile, oldLock)
                    clearPending()
                } catch (_: IOException) {
                    profileFailure(ProfileFailure.RecoveryRequired)
                }
            }
            throw failure
        }

        clearPending()
    }

    override suspend fun <T> withSnapshot(
        snapshot: ProfileSnapshot,
        action: suspend () -> ProfileResult<T>,
    ): ProfileResult<T> = locked {
        if (readSnapshot() != snapshot) profileFailure(ProfileFailure.StalePlan)

        when (val result = action()) {
            is ProfileResult.Success -> result.value
            is ProfileResult.Failed -> profileFailure(result.failure)
        }
    }

    private fun readSnapshot(): ProfileSnapshot {
        val yaml = readFile(profileFile)
        val json = readFile(lockFile)
        val profile = yaml?.let(codec::decodeProfile)
        val lock = json?.let(codec::decodeLock)
        if (lock != null && (profile == null || lock.profileId != profile.id || lock.game != profile.game)) {
            profileFailure(ProfileFailure.InvalidLock)
        }

        val digest = MessageDigest.getInstance("SHA-256")
        for (text in listOf(yaml, json)) {
            val value = text?.encodeToByteArray()
            digest.update((value?.size ?: -1).toString().encodeToByteArray())
            digest.update(0.toByte())
            value?.let(digest::update)
        }

        return ProfileSnapshot(profile, lock, digest.digest().toHexString())
    }

    private fun readFile(path: Path): String? {
        if (!path.exists(NOFOLLOW_LINKS)) return null

        if (!path.isRegularFile(NOFOLLOW_LINKS) || path.fileSize() > 4 * 1024 * 1024) {
            profileFailure(ProfileFailure.InvalidProfile)
        }

        return path.readText()
    }

    private fun replace(path: Path, content: String) {
        val temporary = Files.createTempFile(directory, ".knitty-write-", ".tmp")
        try {
            temporary.writeText(content)
            replaceFile(temporary, path)
        } finally {
            temporary.deleteIfExists()
        }
    }

    private fun restore(path: Path, content: String?) {
        if (content == null) path.deleteIfExists() else replace(path, content)
    }

    private fun clearPending() {
        pending.listDirectoryEntries().filter { it.name != "committed" }.forEach { it.deleteExisting() }
        pending.resolve("committed").deleteIfExists()
        pending.deleteExisting()
    }

    private suspend fun <T> locked(block: suspend () -> T): ProfileResult<T> = withContext(Dispatchers.IO) {
        guarded {
            directory.createDirectories()
            val mutex = directory.resolve(".knitty-profile.lock")
            if (mutex.isSymbolicLink()) profileFailure(ProfileFailure.InvalidProfile)
            withExclusiveFileLock(mutex, onBusy = { profileFailure(ProfileFailure.Busy) }) {
                if (pending.exists(NOFOLLOW_LINKS)) profileFailure(ProfileFailure.RecoveryRequired)
                block()
            }
        }
    }

    private suspend fun <T> guarded(block: suspend () -> T): ProfileResult<T> = try {
        ProfileResult.Success(block())
    } catch (failure: ProfileFileFailure) {
        ProfileResult.Failed(failure.failure)
    } catch (_: IOException) {
        ProfileResult.Failed(ProfileFailure.FilesystemFailure)
    }
}
