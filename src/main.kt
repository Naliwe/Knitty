package knitty

import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.main
import knitty.cli.knittyCommand
import knitty.providers.providerHttpClient
import knitty.settings.EnvironmentFailure
import knitty.settings.EnvironmentOutcome
import knitty.settings.ProfileEnvironment
import knitty.settings.localSettingsPath
import knitty.terminal.plainText
import knitty.tui.openSearchScreen
import kotlinx.coroutines.runBlocking
import java.io.IOException
import kotlin.io.path.Path

fun main(args: Array<String>) {
    providerHttpClient().use { client ->
        ApplicationServices(client).use { services ->
            val defaultProfile by lazy { services.profile(".", loadEnvironment(".")) }
            knittyCommand(
                { request -> defaultProfile.search.search(request) },
                { directory -> services.profile(directory, loadEnvironment(directory)).profiles },
                services.setupSteamLibrary,
                services.configureModio,
            ) { game, directory, target ->
                val environment = loadEnvironment(directory)
                val profile = services.profile(directory, environment)

                try {
                    runBlocking { openSearchScreen(game, profile.search, profile.profiles, target) }
                } catch (_: IOException) {
                    throw CliktError("Cannot open the terminal. Run knitty tui in an interactive terminal.")
                }
            }.main(args)
        }
    }
}

private fun loadEnvironment(directory: String): ProfileEnvironment {
    val config = localSettingsPath().parent
    val defaults = loadEnvironmentFile(config.toString(), "modio.env")
    return loadEnvironmentFile(directory, ".env", defaults)
}

private fun loadEnvironmentFile(
    directory: String,
    fileName: String,
    defaults: ProfileEnvironment? = null,
): ProfileEnvironment {
    val result = runBlocking { ProfileEnvironment.load(Path(directory), defaults = defaults, fileName = fileName) }
    return when (result) {
        is EnvironmentOutcome.Loaded -> result.environment
        is EnvironmentOutcome.Failed -> {
            val reason = when (val failure = result.failure) {
                EnvironmentFailure.Unreadable -> "could not read the file"
                EnvironmentFailure.TooLarge -> "file exceeds 64 KiB"
                EnvironmentFailure.InvalidEncoding -> "expected UTF-8 text"
                is EnvironmentFailure.InvalidLine -> "invalid or duplicate assignment on line ${failure.number}"
            }

            throw CliktError("Cannot load ${plainText(Path(directory).resolve(fileName).toString())}: $reason.")
        }
    }
}
