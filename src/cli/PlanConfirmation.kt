package knitty.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.terminal
import com.github.ajalt.mordant.terminal.YesNoPrompt

internal fun CliktCommand.confirmPlan(
    lines: List<String>,
    question: String,
    dryRun: Boolean,
    yes: Boolean,
    announceCancellation: Boolean = true,
): Boolean {
    printLines(lines)
    printLine("")

    if (dryRun) {
        printStatus("No files changed.")
        return false
    }

    if (yes || YesNoPrompt("${CliStyle.accent("::")} $question", terminal, default = false).ask() == true) {
        return true
    }

    if (announceCancellation) {
        printStatus("Cancelled. No files changed.")
    }
    return false
}
