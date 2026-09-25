package knitty.cli

import com.github.ajalt.clikt.testing.test
import knitty.*
import knitty.core.model.*
import kotlin.test.*

class ProfileCommandTest {
    @Test
    fun serverTargetUsesSharedSyncAndDryRunDisplaysDowntimeWithoutApplying() {
        var applied = false
        val service = object : ProfileFake() {
            override suspend fun planSync(
                game: GameId,
                directory: String?,
                target: String?,
                progress: ProgressSink,
            ): ProfileResult<SyncPlan> {
                assertEquals("ovh", target)
                assertNull(directory)
                return ProfileResult.Success(sampleServerSync)
            }

            override suspend fun apply(plan: SyncPlan, progress: ProgressSink): ProfileResult<Unit> {
                assertEquals(sampleServerSync, plan)
                applied = true
                return ProfileResult.Success(Unit)
            }
        }
        val preview = SyncCommand { service }.test("core-keeper --target ovh --dry-run")
        assertEquals(0, preview.statusCode, preview.output)
        assertFalse(applied)
        assertContains(preview.stdout, "stop the server")
        assertContains(preview.stdout, "auto-update")
        assertContains(preview.stdout, "panel.example.com")

        assertEquals(0, SyncCommand { service }.test("core-keeper --target ovh --yes").statusCode)
        assertTrue(applied)
        assertNotEquals(0, SyncCommand { service }.test("core-keeper --target ovh --installation /local").statusCode)
    }

    @Test
    fun dependencyPlanShowsAutomaticPackagesAndSavesOnlyTheReviewedGraph() {
        var saves = 0
        val service = object : ProfileFake() {
            override suspend fun add(game: GameId, reference: PackageReference) =
                ProfileResult.Success(sampleDependencyEdit)

            override suspend fun save(plan: ProfileEditPlan): ProfileResult<Unit> {
                assertEquals(sampleDependencyEdit, plan)
                saves++
                return ProfileResult.Success(Unit)
            }
        }
        for (option in listOf("--dry-run", "--yes")) {
            val result = ProfileEditCommand("add") { service }.test("core-keeper modio:1 $option")
            assertEquals(0, result.statusCode, result.output)
            assertContains(result.stdout, "Shared Library")
            assertContains(result.stdout, "dependency")
            assertContains(result.stdout, "Requires: modio:2")
            assertContains(result.stdout, "2 add")
        }
        assertEquals(1, saves)
    }

    @Test
    fun addAndRemoveUseProfilePortsAndNeverSaveDryRunsOrDeclinedPlans() {
        for (action in listOf("add", "remove")) {
            for ((options, input, saves) in listOf(
                Triple(" --dry-run", "", 0),
                Triple("", "n\n", 0),
                Triple("", "y\n", 1),
            )) {
                var calls = 0
                val service = object : ProfileFake() {
                    override suspend fun add(
                        game: GameId,
                        reference: PackageReference,
                    ): ProfileResult<ProfileEditPlan> {
                        assertEquals(profileGame, game)
                        assertEquals(PackageReference("modio", "1"), reference)
                        return ProfileResult.Success(sampleEdit)
                    }

                    override suspend fun remove(game: GameId, id: PackageId): ProfileResult<ProfileEditPlan> {
                        assertEquals(profileGame, game)
                        assertEquals(profilePackage.id, id)
                        return ProfileResult.Success(sampleEdit)
                    }

                    override suspend fun save(plan: ProfileEditPlan): ProfileResult<Unit> {
                        assertEquals(sampleEdit, plan)
                        calls++
                        return ProfileResult.Success(Unit)
                    }
                }
                val result = ProfileEditCommand(action) { path ->
                    assertEquals("/shared", path)
                    service
                }
                    .test("core-keeper modio:1 --profile /shared$options", stdin = input)
                assertEquals(0, result.statusCode, result.output)
                assertEquals(saves, calls)
                assertContains(result.stdout, "file 10")
            }
        }
    }

    @Test
    fun syncReviewsUntouchedModsAndAppliesOnlyConfirmedExactPlan() {
        for (dryRun in listOf(true, false)) {
            var applied = false
            val service = object : ProfileFake() {
                override suspend fun planSync(
                    game: GameId,
                    directory: String?,
                    target: String?, progress: ProgressSink,
                ): ProfileResult<SyncPlan> {
                    assertEquals(profileGame, game)
                    assertEquals("/chosen", directory)
                    return ProfileResult.Success(sampleSync)
                }

                override suspend fun apply(plan: SyncPlan, progress: ProgressSink): ProfileResult<Unit> {
                    assertEquals(sampleSync, plan)
                    applied = true
                    return ProfileResult.Success(Unit)
                }
            }
            val args = "core-keeper --installation /chosen " + if (dryRun) "--dry-run" else "--yes"
            val result = SyncCommand { service }.test(args)
            assertEquals(0, result.statusCode, result.output)
            assertEquals(!dryRun, applied)
            assertContains(result.stdout, "Outside profile, preserved: manual")
            assertContains(result.stdout, "/game/Mods")
        }
    }

    @Test
    fun syncFailuresAreActionableAndNonzero() {
        val service = object : ProfileFake() {
            override suspend fun planSync(game: GameId, directory: String?, target: String?, progress: ProgressSink) =
                ProfileResult.Failed(ProfileFailure.LockOutOfDate)
        }
        val result = SyncCommand { service }.test("core-keeper --yes")
        assertNotEquals(0, result.statusCode)
        assertContains(result.stderr, "knitty lock")
        assertFalse(result.stdout.contains("synchronized"))
    }
}
