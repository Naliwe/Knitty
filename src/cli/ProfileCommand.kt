package knitty.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import knitty.core.application.ManageProfile
import knitty.core.model.*
import knitty.terminal.plainText

class ProfileEditCommand(
    private val action: String,
    private val profiles: (String) -> ManageProfile,
) : CliktCommand(name = action) {
    override fun help(context: Context): String =
        if (action == "add") "Review adding a mod to the profile." else "Review removing a mod from the profile."

    private val game by argument()
    private val packageName by argument(name = "package")
    private val directory by option(
        "--profile",
        help = "Directory containing knitty.yaml and knitty.lock.",
    ).default(".")
    private val dryRun by option("--dry-run").flag()
    private val yes by option("--yes", "-y").flag()

    override fun run() {
        val parts = packageName.split(':', limit = 2)
        if (parts.size != 2 || parts.any { it.isBlank() }) {
            throw CliktError("Use provider:id (or a slug for add), such as modio:3177992.")
        }

        val (provider, value) = parts
        val service = profiles(directory)
        val result = withProgress(ProgressStage.Resolving) {
            if (action == "add") {
                service.add(GameId(game), PackageReference(provider, value))
            } else {
                service.remove(GameId(game), PackageId(provider, value))
            }
        }

        finishEdit(service, result.requireValue())
    }

    private fun finishEdit(service: ManageProfile, plan: ProfileEditPlan) {
        val confirmed = confirmPlan(
            renderProfileEdit(plan),
            question = "Save profile and lockfile?",
            dryRun = dryRun,
            yes = yes,
        )
        if (!confirmed) return

        withProgress(ProgressStage.SavingProfile) { service.save(plan) }.requireValue()
        val syncCommand = "knitty sync ${plainText(game)} --profile ${plainText(directory)}"
        printStatus("Saved knitty.yaml and knitty.lock. Run $syncCommand to deploy.")
    }
}

class LockProfileCommand(private val profiles: (String) -> ManageProfile) : CliktCommand(name = "lock") {
    override fun help(context: Context): String = "Resolve missing pins and review the lockfile."

    private val game by argument()
    private val directory by option("--profile").default(".")
    private val dryRun by option("--dry-run").flag()
    private val yes by option("--yes", "-y").flag()

    override fun run() {
        val service = profiles(directory)
        val plan = withProgress(ProgressStage.Resolving) { service.lock(GameId(game)) }.requireValue()
        val confirmed = confirmPlan(
            renderProfileEdit(plan),
            question = "Save the resolved lockfile?",
            dryRun = dryRun,
            yes = yes,
            announceCancellation = false,
        )
        if (!confirmed) return

        withProgress(ProgressStage.SavingProfile) { service.save(plan) }.requireValue()
        printStatus("Profile locked. Existing pins preserved.")
    }
}

class SyncCommand(private val profiles: (String) -> ManageProfile) : CliktCommand(name = "sync") {
    override fun help(context: Context): String = "Review and deploy the locked profile."

    private val game by argument()
    private val directory by option("--profile").default(".")
    private val installation by option("--installation")
    private val target by option("--target", help = "Named Pelican server in the profile; omitted means local sync.")
    private val dryRun by option("--dry-run").flag()
    private val yes by option("--yes", "-y").flag()

    override fun run() {
        val service = profiles(directory)
        if (target != null && installation != null) throw CliktError("Use either --target or --installation.")
        val plan = withProgress(ProgressStage.Checking) { progress ->
            service.planSync(
                GameId(game),
                installation,
                target,
                progress,
            )
        }.requireValue()
        val confirmed = confirmPlan(
            renderSyncPlan(plan),
            question = "Apply this sync plan?",
            dryRun = dryRun,
            yes = yes,
        )
        if (!confirmed) return

        withProgress(ProgressStage.Checking) { progress -> service.apply(plan, progress) }.requireValue()
        printStatus("Profile synchronized.")
    }
}

class OutdatedCommand(private val profiles: (String) -> ManageProfile) : CliktCommand(name = "outdated") {
    override fun help(context: Context): String = "Show published releases that differ from your pins."

    private val game by argument()
    private val directory by option("--profile").default(".")

    override fun run() {
        val plan = withProgress(ProgressStage.Resolving) { profiles(directory).planUpdate(GameId(game)) }.requireValue()
        printLines(renderUpdates(plan))
    }
}

class UpdateProfileCommand(private val profiles: (String) -> ManageProfile) : CliktCommand(name = "update") {
    override fun help(context: Context): String = "Review new pins before saving the lockfile."

    private val game by argument()
    private val directory by option("--profile").default(".")
    private val dryRun by option("--dry-run").flag()
    private val yes by option("--yes", "-y").flag()

    override fun run() {
        val service = profiles(directory)
        val plan = withProgress(ProgressStage.Resolving) { service.planUpdate(GameId(game)) }.requireValue()
        if (!plan.hasChanges) {
            printLines(renderUpdates(plan))
            return
        }

        val confirmed = confirmPlan(
            renderUpdates(plan) + "Save exact pins; deploy separately with sync.",
            question = "Save these updates to the lockfile?",
            dryRun = dryRun,
            yes = yes,
        )
        if (!confirmed) return

        withProgress(ProgressStage.SavingProfile) { service.save(plan) }.requireValue()
        val syncCommand = "knitty sync ${plainText(game)} --profile ${plainText(directory)}"
        printStatus("Updated knitty.lock. Run $syncCommand to deploy.")
    }
}
