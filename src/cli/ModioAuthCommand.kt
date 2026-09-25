package knitty.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.terminal
import com.github.ajalt.mordant.terminal.YesNoPrompt
import knitty.core.application.ConfigureModio
import knitty.core.model.ModioAccessFailure
import knitty.core.model.ModioAccessOutcome
import knitty.core.model.ModioSetupOutcome
import kotlinx.coroutines.runBlocking

class ModioAuthCommand(private val configure: ConfigureModio) : CliktCommand(name = "auth") {
    override fun help(context: Context): String = "Save your personal mod.io API access for CLI and TUI."

    override fun run() {
        printStatus("Open https://mod.io/me/access and copy your API path and API key.")
        printStatus("API path (https://u-...modapi.io/v1):")
        val path = terminal.readLineOrNull(false)
            ?: throw CliktError("No API path provided.")

        printStatus("API key (hidden):")
        val key = terminal.readLineOrNull(true)
            ?: throw CliktError("No API key provided.")

        val access = when (val planned = configure.plan(path, key)) {
            is ModioAccessOutcome.Valid -> planned.access
            is ModioAccessOutcome.Invalid -> throw CliktError(accessFailureMessage(planned.failure))
        }

        printStatus("Save in your account's Knitty config directory, outside shared profiles.")
        printStatus("The key is stored as local text; Unix file permissions restrict it to your account.")
        if (YesNoPrompt("Save or replace your mod.io access?", terminal, default = false).ask() != true) {
            printStatus("Cancelled. Credentials unchanged.")
            return
        }

        when (runBlocking { configure.save(access) }) {
            ModioSetupOutcome.Saved -> printStatus("Saved for CLI and TUI. Test access with knitty search core-keeper storage.")
            ModioSetupOutcome.FilesystemFailure -> throw CliktError("Could not save mod.io access. Check config directory permissions.")
        }
    }
}

private fun accessFailureMessage(failure: ModioAccessFailure): String = when (failure) {
    ModioAccessFailure.InvalidEndpoint ->
        "Invalid API path. Copy the complete HTTPS API path from https://mod.io/me/access (https://u-...modapi.io/v1). Nothing was saved."

    ModioAccessFailure.EmptyKey ->
        "No API key was received. Run knitty auth again, paste at the hidden key prompt, then press Enter. Nothing was saved."

    ModioAccessFailure.KeyTooLong ->
        "The API key input is too long. Copy only the API key from https://mod.io/me/access. Nothing was saved."

    ModioAccessFailure.InvalidKeyCharacters ->
        "The API key input contains whitespace, quotes, control characters, or non-ASCII text. Paste only the key; try right-click paste if Ctrl+V is not working. Nothing was saved."
}
