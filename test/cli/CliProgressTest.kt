package knitty.cli

import com.github.ajalt.clikt.testing.test
import knitty.ProfileFake
import knitty.core.model.*
import knitty.sampleSync
import kotlin.test.*

class CliProgressTest {
    @Test
    fun redirectedOutputSummarizesTransfersWithoutAnimationOrFalseCompletion() {
        for (fail in listOf(false, true)) {
            val service = object : ProfileFake() {
                override suspend fun planSync(
                    game: GameId, directory: String?, target: String?, progress: ProgressSink,
                ) = ProfileResult.Success(sampleSync)

                override suspend fun apply(plan: SyncPlan, progress: ProgressSink): ProfileResult<Unit> {
                    val count = if (fail) 50 else 100
                    repeat(count + 1) { bytes ->
                        progress.report(
                            OperationProgress(
                                ProgressStage.Downloading,
                                "Mod\u001b[2J",
                                bytes.toLong(),
                                100,
                            ),
                        )
                    }
                    return if (fail) ProfileResult.Failed(ProfileFailure.FilesystemFailure) else ProfileResult.Success(
                        Unit,
                    )
                }
            }
            val result = SyncCommand { service }.test("core-keeper --yes")

            assertContains(result.stdout, ":: Downloading packages")
            assertContains(result.stdout, if (fail) "50%" else "100%")
            assertTrue(result.stdout.lines().size < 30, "Piped output must not print every transfer chunk")
            assertFalse(result.stdout.contains('\r'))
            assertFalse(result.stdout.contains('\u001b'))
            assertEquals(!fail, result.statusCode == 0)
            if (fail) {
                assertFalse(result.stdout.contains("100%"))
                assertFalse(result.stdout.contains("Profile synchronized"))
            }
        }
    }
}
