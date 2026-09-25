package knitty.cli

import com.github.ajalt.clikt.testing.test
import knitty.*
import knitty.core.model.GameId
import knitty.core.model.ProfileEditPlan
import knitty.core.model.ProfileFailure
import knitty.core.model.ProfileResult
import kotlin.test.*

class UpdateCommandTest {
    @Test
    fun outdatedUsesSharedPlannerAndNeverSaves() {
        val service = object : ProfileFake() {
            override suspend fun planUpdate(game: GameId): ProfileResult<ProfileEditPlan> {
                assertEquals(profileGame, game)
                return ProfileResult.Success(sampleUpdate)
            }
        }
        val command = knittyCommand(
            { error("Must not search") },
            { path ->
                assertEquals("/shared", path)
                service
            },
            unusedSetup,
        ) { _, _, _ -> error("Must not open TUI") }

        val result = command.test("outdated core-keeper --profile /shared")

        assertEquals(0, result.statusCode, result.output)
        assertContains(result.stdout, "1.0 (file 10) -> 2.0 (file 11)")
        assertFalse(result.stdout.contains("Save"))
    }

    @Test
    fun updateSavesOnlyTheReviewedPlanAfterConfirmation() {
        for ((options, input, expectedSaves) in listOf(
            Triple("--dry-run", "", 0),
            Triple("--dry-run --yes", "", 0),
            Triple("", "n\n", 0),
            Triple("", "y\n", 1),
            Triple("--yes", "", 1),
        )) {
            var saves = 0
            val service = object : ProfileFake() {
                override suspend fun planUpdate(game: GameId): ProfileResult<ProfileEditPlan> {
                    assertEquals(profileGame, game)
                    return ProfileResult.Success(sampleUpdate)
                }

                override suspend fun save(plan: ProfileEditPlan): ProfileResult<Unit> {
                    assertEquals(sampleUpdate, plan)
                    saves++
                    return ProfileResult.Success(Unit)
                }
            }
            val command = knittyCommand(
                { error("Must not search") },
                { path ->
                    assertEquals("/shared", path)
                    service
                },
                unusedSetup,
            ) { _, _, _ -> error("Must not open TUI") }

            val result = command.test("update core-keeper --profile /shared $options", stdin = input)

            assertEquals(0, result.statusCode, result.output)
            assertEquals(expectedSaves, saves)
            assertContains(result.stdout, "1.0 (file 10) -> 2.0 (file 11)")
            if (saves == 1) assertContains(result.stdout, "knitty sync core-keeper --profile /shared")
        }
    }

    @Test
    fun unchangedPinsNeverPromptOrWriteAndFailuresExitNonzero() {
        val unchanged = object : ProfileFake() {
            override suspend fun planUpdate(game: GameId) = ProfileResult.Success(sampleUpdate.copy(lock = sampleLock))
        }
        val result = UpdateProfileCommand { unchanged }.test("core-keeper --yes")
        assertEquals(0, result.statusCode, result.output)
        assertContains(result.stdout, "All locked packages match")

        val failed = object : ProfileFake() {
            override suspend fun planUpdate(game: GameId) = ProfileResult.Failed(ProfileFailure.LockOutOfDate)
        }
        for (command in listOf(UpdateProfileCommand { failed }, OutdatedCommand { failed })) {
            val failure = command.test("core-keeper")
            assertNotEquals(0, failure.statusCode)
            assertContains(failure.stderr, "lock")
        }
    }
}
