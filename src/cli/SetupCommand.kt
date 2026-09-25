package knitty.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.terminal
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.mordant.terminal.YesNoPrompt
import knitty.core.application.SetupSteamLibrary
import knitty.core.model.SaveSetupOutcome
import knitty.core.model.SetupFailure
import knitty.core.model.SetupPlanOutcome
import knitty.terminal.plainText
import kotlinx.coroutines.runBlocking

class SetupCommand(private val setup: SetupSteamLibrary) : CliktCommand(name = "setup") {
    override fun help(context: Context): String = "Choose the Steam library used by both interfaces."

    private val steamLibrary by option("--steam-library", help = "Steam library folder containing steamapps.")
    private val yes by option("--yes", "-y", help = "Save the validated library without confirmation.").flag()

    override fun run() {
        val directory = steamLibrary ?: run {
            printStatus("Steam library folder (the folder containing steamapps):")
            terminal.readLineOrNull(false)
                ?: throw CliktError("No path provided. Use setup --steam-library PATH.")
        }

        val outcome = runBlocking { setup.plan(directory) }
        val plan = when (outcome) {
            is SetupPlanOutcome.Planned -> outcome.plan
            is SetupPlanOutcome.Failed -> throw CliktError(setupFailureMessage(outcome.failure))
        }

        printStatus("Steam library: ${plainText(plan.directory)}")
        if (!yes && YesNoPrompt(
                "${CliStyle.accent("::")} Save this library for CLI and TUI?",
                terminal,
                default = true,
            ).ask() != true
        ) {
            printStatus("Cancelled. Settings unchanged.")
            return
        }

        when (val saved = runBlocking { setup.save(plan) }) {
            SaveSetupOutcome.Saved -> printStatus("Saved. CLI and TUI will use this Steam library.")
            is SaveSetupOutcome.Failed -> throw CliktError(setupFailureMessage(saved.failure))
        }
    }
}

private fun setupFailureMessage(failure: SetupFailure): String = when (failure) {
    SetupFailure.InvalidLibrary -> "Steam library not found. Choose the folder containing steamapps, not a game folder."
    SetupFailure.InvalidSettings -> "Knitty's local settings are invalid or from an unsupported version. Check knitty/settings.json in your config directory."
    SetupFailure.FilesystemFailure -> "Could not read or save setup. Check directory access and config directory write permissions."
}
