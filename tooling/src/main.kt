package knitty.tooling

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.parameters.types.path
import java.io.IOException
import java.time.LocalDate
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    val command = Tooling().subcommands(PackageRelease(), PackageSource(), PackageWindows(), Manifests(), CheckSftp())

    try {
        command.main(args)
    } catch (failure: IllegalArgumentException) {
        command.echo(failure.message ?: "Invalid tooling input.", err = true)
        exitProcess(1)
    } catch (failure: IOException) {
        command.echo(failure.message ?: "Tooling filesystem operation failed.", err = true)
        exitProcess(1)
    } catch (failure: IllegalStateException) {
        command.echo(failure.message ?: "Tooling command failed.", err = true)
        exitProcess(1)
    }
}

private class Tooling : CliktCommand(name = "knitty-tooling") {
    override fun run() = Unit
}

private class PackageRelease : CliktCommand(name = "package-release") {
    private val version by option().required()
    private val platform by option().choice("jvm", "windows-x64").required()
    private val jpackage by option().path(mustExist = true, canBeDir = false).default(defaultJpackage())

    override fun run() {
        echo(PublicRelease(projectRoot()).assemble(version, platform, jpackage))
    }
}

private class PackageWindows : CliktCommand(name = "package-windows") {
    private val profile by option().path(mustExist = true, canBeFile = false)

    override fun run() {
        echo(WindowsBundle(projectRoot()).assemble(profile))
    }
}

private class PackageSource : CliktCommand(name = "package-source") {
    private val version by option().required()

    override fun run() {
        echo(SourceRelease(projectRoot()).assemble(version))
    }
}

private class Manifests : CliktCommand(name = "manifests") {
    private val version by option().required()
    private val license by option().default("MIT")
    private val target by option().choice("all", "aur", "winget").default("all")
    private val date by option().default(LocalDate.now().toString())
    private val artifacts by option().path()
    private val output by option().path()
    private val srcinfo by option().flag()

    override fun run() {
        require(!srcinfo || target != "winget") { "--srcinfo requires AUR manifests." }
        val root = projectRoot()
        val manifests = ReleaseManifests(root)
        val directories = manifests.generate(
            version = version,
            license = license,
            artifacts = artifacts ?: root.resolve("dist"),
            output = output ?: root.resolve("dist/manifests"),
            date = date,
            target = target,
        )

        directories.aur.forEach { directory ->
            if (srcinfo) manifests.sourceInfo(directory)
            echo("AUR: $directory")
        }
        directories.winget?.let { echo("WinGet: $it") }
    }
}

private class CheckSftp : CliktCommand(name = "check-sftp") {
    override fun run() = LocalSftpCheck(projectRoot()).run()
}
