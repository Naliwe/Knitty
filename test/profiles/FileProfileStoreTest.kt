package knitty.profiles

import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption.*
import knitty.core.model.*
import knitty.sampleLock
import knitty.sampleProfile
import kotlin.io.path.*
import kotlin.test.*
import kotlinx.coroutines.test.runTest

class FileProfileStoreTest {
    @Test
    fun secondFileCommitFailureRestoresOriginalPairAndAllowsRetry() = runTest {
        val root = createTempDirectory("knitty-pair-")
        try {
            val store = FileProfileStore(root)
            val empty = assertIs<ProfileResult.Success<ProfileSnapshot>>(store.read()).value
            assertIs<ProfileResult.Success<Unit>>(store.save(ProfileEditPlan(empty, sampleProfile, sampleLock)))
            val before = assertIs<ProfileResult.Success<ProfileSnapshot>>(store.read()).value
            val yaml = root.resolve("knitty.yaml").readText()
            val lock = root.resolve("knitty.lock").readText()
            var calls = 0
            val failing = FileProfileStore(root) { source, target ->
                if (++calls == 2) throw IOException("second write failed")
                Files.move(source, target, ATOMIC_MOVE, REPLACE_EXISTING)
            }
            val edit = ProfileEditPlan(
                before,
                sampleProfile.copy(packages = emptyList()),
                sampleLock.copy(packages = emptyList()),
            )
            assertEquals(ProfileResult.Failed(ProfileFailure.FilesystemFailure), failing.save(edit))
            assertEquals(yaml, root.resolve("knitty.yaml").readText())
            assertEquals(lock, root.resolve("knitty.lock").readText())
            assertFalse(root.resolve(".knitty-profile-pending").exists())
            assertIs<ProfileResult.Success<Unit>>(store.save(edit))
        } finally {
            Files.walk(root).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach { it.deleteIfExists() }
            }
        }
    }

    @Test
    fun interruptedPairIsReportedWithoutMutatingDuringRead() = runTest {
        val root = createTempDirectory("knitty-pair-")
        try {
            val pending = root.resolve(".knitty-profile-pending").createDirectory()
            pending.resolve("knitty.yaml.before").writeText("preserve")
            assertEquals(ProfileResult.Failed(ProfileFailure.RecoveryRequired), FileProfileStore(root).read())
            assertEquals("preserve", pending.resolve("knitty.yaml.before").readText())
            assertFalse(root.resolve("knitty.yaml").exists())
        } finally {
            Files.walk(root).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach { it.deleteIfExists() }
            }
        }
    }
}
