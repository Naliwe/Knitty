package knitty.cli

import com.github.ajalt.clikt.core.*
import com.github.ajalt.clikt.output.MordantHelpFormatter
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.default
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import knitty.core.application.ConfigureModio
import knitty.core.application.ManageProfile
import knitty.core.application.SearchMods
import knitty.core.application.SearchRequest
import knitty.core.application.SetupSteamLibrary
import knitty.core.model.GameId
import knitty.core.model.SearchFailure
import knitty.core.model.SearchOutcome
import knitty.terminal.plainText
import knitty.terminal.providerFailureMessage
import kotlinx.coroutines.runBlocking

fun knittyCommand(
    searchMods: SearchMods,
    profiles: (String) -> ManageProfile,
    setupSteamLibrary: SetupSteamLibrary,
    configureModio: ConfigureModio? = null,
    openTui: (GameId, String, String?) -> Unit,
): CliktCommand = KnittyCommand().apply {
    configureModio?.let { subcommands(ModioAuthCommand(it)) }
}.subcommands(
    SetupCommand(setupSteamLibrary),
    SearchCommand(searchMods),
    ProfileEditCommand("add", profiles),
    ProfileEditCommand("remove", profiles),
    LockProfileCommand(profiles),
    UpdateProfileCommand(profiles),
    OutdatedCommand(profiles),
    SyncCommand(profiles),
    TuiCommand(openTui),
)

private class KnittyCommand : CliktCommand(name = "knitty") {
    init {
        context {
            helpFormatter = { context ->
                object : MordantHelpFormatter(context) {
                    override fun styleSectionTitle(title: String): String = CliStyle.accent(title)
                    override fun styleUsageTitle(title: String): String = CliStyle.accent(title)
                    override fun styleSubcommandName(name: String): String = CliStyle.name(name)
                    override fun styleOptionName(name: String): String = CliStyle.accent(name)
                    override fun styleArgumentName(name: String): String = CliStyle.accent(name)
                }
            }
        }
    }

    override fun help(context: Context): String = "Mods, in good company. Search, review, then sync."

    override fun helpEpilog(context: Context): String =
        "Start here: knitty tui core-keeper  •  knitty search core-keeper storage"

    override fun run() = Unit
}

class SearchCommand(private val searchMods: SearchMods) : CliktCommand(name = "search") {
    override fun help(context: Context): String = "Find mods in a game catalog."

    private val game by argument()
    private val query by argument().multiple(required = true)
    private val offset by option("--offset", help = "Result offset for the next page.").int().default(0)

    override fun run() {
        val request = SearchRequest(GameId(game), query.joinToString(" "), offset)
        val outcome = runBlocking { searchMods.search(request) }

        when (outcome) {
            is SearchOutcome.Failed -> throw CliktError(failureMessage(outcome.failure))
            is SearchOutcome.Found -> {
                val page = outcome.page
                printStatus("Search · ${plainText(game)} · ${plainText(query.joinToString(" "))}")
                if (page.mods.isEmpty()) printLine("No mods found. Try a shorter query.")
                page.mods.forEach { mod ->
                    val reference = "${mod.id.provider}:${mod.id.value}"
                    printLine(
                        "${CliStyle.version(reference)}  ${CliStyle.name(mod.name)}  " +
                            CliStyle.version(mod.version ?: "unavailable"),
                    )
                    printLine("    ${plainText(mod.summary)}")
                    printLine(CliStyle.muted("    By ${mod.author} · ${mod.pageUrl}"))
                }
                printLine("")
                printLine(CliStyle.muted("Showing ${page.mods.size} of ${page.total} results (offset ${page.offset})."))
                page.nextOffset?.let { printLine("Next page: repeat this search with ${CliStyle.version("--offset $it")}") }
            }
        }
    }
}

private class TuiCommand(private val openTui: (GameId, String, String?) -> Unit) : CliktCommand(name = "tui") {
    override fun help(context: Context): String = "Browse mods and review changes interactively."

    private val game by argument().default("core-keeper")
    private val directory by option("--profile", help = "Directory containing the shared profile.").default(".")
    private val target by option("--target", help = "Named Pelican server to use when reviewing sync.")
    override fun run() = openTui(GameId(game), directory, target)
}

private fun failureMessage(failure: SearchFailure): String = when (failure) {
    is SearchFailure.UnsupportedGame -> "Unsupported game '${plainText(failure.game.value)}'. Search core-keeper or valheim."
    SearchFailure.EmptyQuery -> "Enter a search query."
    SearchFailure.InvalidOffset -> "Result offset must be zero or greater."
    is SearchFailure.Provider -> providerFailureMessage(failure.failure, failure.provider)
}
