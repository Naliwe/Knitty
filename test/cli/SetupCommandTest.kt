package knitty.cli

import com.github.ajalt.clikt.testing.test
import knitty.core.application.SetupSteamLibrary
import knitty.core.model.SaveSetupOutcome
import knitty.core.model.SetupFailure
import knitty.core.model.SetupPlanOutcome
import knitty.core.model.SteamLibraryPlan
import kotlin.test.*

class SetupCommandTest {
    @Test
    fun interactiveSetupShowsValidatedPathAndSavesOnlyOnConfirmation() {
        for ((answer, saved) in listOf("y" to true, "n" to false)) {
            var received: String? = null
            var savedPlan: SteamLibraryPlan? = null
            val plan = SteamLibraryPlan("/home/example/Games/SteamLibrary")
            val setup = object : SetupSteamLibrary {
                override suspend fun plan(directory: String): SetupPlanOutcome {
                    received = directory
                    return SetupPlanOutcome.Planned(plan)
                }

                override suspend fun save(plan: SteamLibraryPlan): SaveSetupOutcome {
                    savedPlan = plan
                    return SaveSetupOutcome.Saved
                }
            }
            val result = SetupCommand(setup).test("", stdin = "~/Games/SteamLibrary/\n$answer\n")

            assertEquals(0, result.statusCode, result.output)
            assertEquals("~/Games/SteamLibrary/", received)
            assertEquals(if (saved) plan else null, savedPlan)
            assertContains(result.stdout, plan.directory)
            assertContains(result.stdout, if (saved) "Saved" else "Cancelled")
        }
    }

    @Test
    fun explicitSetupPassesPathToApplicationAndReportsSaveFailure() {
        val setup = object : SetupSteamLibrary {
            override suspend fun plan(directory: String): SetupPlanOutcome {
                assertEquals("/my library", directory)
                return SetupPlanOutcome.Planned(SteamLibraryPlan(directory))
            }

            override suspend fun save(plan: SteamLibraryPlan) = SaveSetupOutcome.Failed(SetupFailure.FilesystemFailure)
        }
        val result = SetupCommand(setup).test(listOf("--steam-library", "/my library", "--yes"))

        assertNotEquals(0, result.statusCode)
        assertContains(result.stderr, "permissions")
        assertFalse(result.stdout.contains("Saved"))
    }

    @Test
    fun invalidLibraryNeverSaves() {
        val setup = object : SetupSteamLibrary {
            override suspend fun plan(directory: String) = SetupPlanOutcome.Failed(SetupFailure.InvalidLibrary)
            override suspend fun save(plan: SteamLibraryPlan): SaveSetupOutcome = error("Must not save")
        }
        val result = SetupCommand(setup).test("--steam-library /missing --yes")

        assertNotEquals(0, result.statusCode)
        assertContains(result.stderr, "steamapps")
    }
}
