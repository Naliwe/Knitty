package knitty.cli

import com.github.ajalt.clikt.testing.test
import knitty.core.model.ModioSetupOutcome
import knitty.unusedSetup
import kotlin.test.*

class ModioAuthCommandTest {
    @Test
    fun rootAuthCommandSavesOnlyAfterConfirmationWithoutPrintingTheKey() {
        for (answer in listOf("y", "n")) {
            var saved = false
            val command = knittyCommand(
                { error("Must not search") },
                { error("Must not load profiles") },
                unusedSetup,
                { access ->
                    assertEquals("https://u-123.modapi.io/v1", access.apiPath)
                    assertEquals("secret123", access.apiKey)
                    saved = true
                    ModioSetupOutcome.Saved
                },
            ) { _, _, _ -> error("Must not open TUI") }

            val result = command.test("auth", stdin = "https://u-123.modapi.io/v1\nsecret123\n$answer\n")

            assertEquals(0, result.statusCode, result.output)
            assertEquals(answer == "y", saved)
            assertFalse(result.output.contains("secret123"))
        }
    }

    @Test
    fun invalidInputExplainsTheFailureBeforeConfirmationWithoutSavingOrEchoingSecrets() {
        for ((path, key, message) in listOf(
            Triple("https://u-123.modapi.io/v1", "", "No API key was received"),
            Triple("https://u-123.modapi.io/v1", "secret\u0016", "control characters"),
            Triple("https://example.com/v1", "secret123", "Invalid API path"),
        )) {
            val result = ModioAuthCommand { error("Must not save") }
                .test("", stdin = "$path\n$key\ny\n")

            assertNotEquals(0, result.statusCode)
            assertContains(result.stderr, message)
            assertContains(result.stderr, "Nothing was saved")
            assertFalse(result.output.contains("Save or replace"))
            assertFalse(result.output.contains("secret"))
        }
    }
}
