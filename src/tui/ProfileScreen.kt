package knitty.tui

import com.googlecode.lanterna.TerminalTextUtils
import com.googlecode.lanterna.screen.Screen
import knitty.core.model.*
import knitty.terminal.*

internal fun renderProfileScreen(
    screen: Screen,
    state: ProfileScreenState,
    scroll: Int,
    progress: OperationProgress? = null,
    tick: Long = 0,
): Int {
    val canvas = ScreenCanvas(screen)
    if (canvas.tooSmall()) return 0

    val title = when (state) {
        ProfileScreenState.Closed -> "PROFILE"
        ProfileScreenState.Planning -> "PREPARING"
        ProfileScreenState.Applying -> "APPLYING"
        is ProfileScreenState.Done -> "COMPLETE"
        is ProfileScreenState.Failed -> "ATTENTION"
        is ProfileScreenState.Outdated -> "RELEASE CHANGES"
        is ProfileScreenState.Edit -> "REVIEW PROFILE"
        is ProfileScreenState.Sync -> "REVIEW SYNC"
    }
    val context = when (state) {
        is ProfileScreenState.Edit -> "${state.plan.profile.game.value} · ${state.plan.profile.id}"
        is ProfileScreenState.Outdated -> state.plan.profile.game.value
        is ProfileScreenState.Sync -> state.plan.installation.game.value
        else -> "Profile & installation"
    }
    canvas.header(context, title)
    canvas.rule(2)
    if (state == ProfileScreenState.Planning || state == ProfileScreenState.Applying) {
        val stage = if (state == ProfileScreenState.Planning) ProgressStage.Resolving else ProgressStage.Preparing
        renderOperationProgress(canvas, progress ?: OperationProgress(stage), tick)
        val hint = if (state == ProfileScreenState.Planning) "Esc cancel   q quit" else "Applying… please wait"
        canvas.footer(hint, "")
        screen.refresh()
        return 0
    }

    val lines = profileLines(state).flatMap { line ->
        val color = when {
            state is ProfileScreenState.Failed -> TuiPalette.removed
            state is ProfileScreenState.Done -> TuiPalette.added
            line.startsWith("+ ") -> TuiPalette.added
            line.startsWith("- ") -> TuiPalette.removed
            line.startsWith("~ ") -> TuiPalette.accent
            line.startsWith("= ") || line.startsWith("  ") -> TuiPalette.muted
            else -> TuiPalette.text
        }
        TerminalTextUtils.getWordWrappedText(canvas.width - 4, plainText(line)).map { it to color }
    }
    val rows = canvas.height - 7
    val start = scroll.coerceIn(0, (lines.size - rows).coerceAtLeast(0))
    lines.drop(start).take(rows).forEachIndexed { index, (line, color) ->
        canvas.text(2, 3 + index, line, canvas.width - 4, color)
    }
    if (lines.size > rows) {
        canvas.text(
            2,
            canvas.height - 4,
            "${start + 1}–${(start + rows).coerceAtMost(lines.size)} / ${lines.size} lines · ↑↓ scroll",
            color = TuiPalette.muted,
        )
    }
    val hint = when (state) {
        ProfileScreenState.Applying -> "Applying… please wait"
        ProfileScreenState.Planning -> "Preparing…   Esc cancel"
        is ProfileScreenState.Edit -> "y save profile   Esc cancel"
        is ProfileScreenState.Sync -> "y apply sync   Esc cancel"
        is ProfileScreenState.Outdated -> if (!state.plan.hasChanges) "Esc return" else "u stage updates   Esc return"
        is ProfileScreenState.Done -> if (state.synchronized) "Esc return" else "s review sync   Esc return"
        is ProfileScreenState.Failed, ProfileScreenState.Closed -> "Esc return"
    }
    val navigation = when (state) {
        ProfileScreenState.Applying -> "Your reviewed changes are being applied."
        else -> "↑↓ scroll   PgUp/PgDn page   q quit"
    }
    canvas.footer(hint, navigation)
    screen.refresh()
    return start
}

private fun renderOperationProgress(canvas: ScreenCanvas, progress: OperationProgress, tick: Long) {
    canvas.text(2, 3, progressTitle(progress.stage), color = TuiPalette.accent, bold = true)
    canvas.text(2, 4, progress.item.orEmpty(), canvas.width - 4, TuiPalette.muted)
    val width = canvas.width - 4
    val fraction = progressFraction(progress)
    canvas.fill(2, 6, width, 1, TuiPalette.surface)
    if (fraction != null) {
        canvas.fill(2, 6, (fraction * width).toInt(), 1, TuiPalette.accent)
    } else {
        for (offset in 0 until 4) {
            canvas.fill(2 + ((tick + offset) % width).toInt(), 6, 1, 1, TuiPalette.accent)
        }
    }
    canvas.text(2, 7, progressAmount(progress), canvas.width - 4, TuiPalette.muted)
}

private fun profileLines(state: ProfileScreenState): List<String> = when (state) {
    ProfileScreenState.Closed -> emptyList()
    ProfileScreenState.Planning -> listOf("Preparing profile plan…")
    ProfileScreenState.Applying -> listOf("Applying the reviewed plan…")
    is ProfileScreenState.Done -> if (state.synchronized) listOf("Profile synchronized.") else listOf(
        "Profile and lockfile saved.",
        "Press s to review and apply sync.",
    )

    is ProfileScreenState.Failed -> listOf("Cannot complete profile operation", profileFailureText(state.failure))
    is ProfileScreenState.Outdated -> buildList {
        add("Published release changes · ${state.plan.profile.game.value}")
        add("")
        if (!state.plan.hasChanges) {
            add("All locked packages match their current published files.")
        } else {
            addAll(profilePackageLines(state.plan, includeKept = false))
            add("Provider-selected releases; version order and game compatibility are not verified.")
            add("Press u to stage these updates. No files have changed.")
        }
    }

    is ProfileScreenState.Edit -> buildList {
        add("Profile plan · ${state.plan.profile.game.value}")
        add("")
        addAll(profilePackageLines(state.plan, includeKept = true))
        add("")
        add("Confirm to save knitty.yaml and knitty.lock.")
        add("Game files change only when you sync.")
    }

    is ProfileScreenState.Sync -> buildList {
        add("Sync plan · ${state.plan.installation.game.value}")
        add("Mods directory: ${state.plan.installation.modsDirectory}")
        state.plan.server?.let { server ->
            add("Server: ${server.target.name} · ${server.target.panelUrl} · ${server.target.serverId}")
            add("SFTP: ${server.target.sftp.host}:${server.target.sftp.port}")
            add(
                if (server.before.power == ServerPowerState.Running) {
                    "Deployment will stop the server and restart it after verification."
                } else {
                    "The server is offline and will remain offline."
                },
            )
            if (server.before.autoUpdate) add("Pelican auto-update is enabled: starting may update the game executable.")
        }
        state.plan.remove.forEach { add("- ${it.name} | file ${it.artifactId.value}") }
        state.plan.install.forEach {
            val role = if (it.id in state.plan.source.profile?.packages.orEmpty()) "requested" else "dependency"
            add("+ ${it.name} · $role | file ${it.artifact.version.artifactId.value}")
        }
        state.plan.keep.forEach { item ->
            val action = if (item in state.plan.adopt) "Adopt into profile:" else "="
            add("$action ${item.name} | file ${item.artifactId.value}")
        }
        state.plan.untouched.forEach { add("Outside profile, preserved: $it") }
        add("")
        add("${state.plan.install.size} install · ${state.plan.remove.size} remove · ${state.plan.keep.size} keep")
        add("Download: ${displaySize(state.plan.install.sumOf { it.artifact.sizeBytes })}")
        if (state.plan.server == null) add("Close Core Keeper before confirming.")
        add("Game compatibility is not verified.")
    }
}

private fun profilePackageLines(plan: ProfileEditPlan, includeKept: Boolean): List<String> = buildList {
    val previous = plan.before.lock?.packages.orEmpty().map { it.id }.toSet()
    val updates = plan.updates.associateBy { it.replacement.id }
    for ((id, name, artifact, dependencies) in plan.lock.packages) {
        val role = if (id in plan.profile.packages) "requested" else "dependency"
        val update = updates[id]
        if (update != null) {
            addAll(updateLines(update))
            add("  $role")
        } else if (id !in previous || includeKept) {
            add("${if (id in previous) "=" else "+"} $name · $role")
            add("  ${artifact.version.label ?: "unlabelled"} · ${id.provider}:${id.value} · file ${artifact.version.artifactId.value}")
        }
        if (dependencies.isNotEmpty() && (update != null || id !in previous || includeKept)) {
            add("  Requires: " + dependencies.joinToString(", ") { "${it.provider}:${it.value}" })
        }
    }
    plan.removals.forEach { add("- ${it.name} [${it.id.provider}:${it.id.value}]") }
    add("")
    add("${plan.additions.size} add · ${updates.size} update · ${plan.removals.size} remove")
    val downloads =
        plan.additions + plan.updates.filter { it.current.artifact != it.replacement.artifact }.map { it.replacement }
    add("Download: ${displaySize(downloads.sumOf { it.artifact.sizeBytes })}")
}

private fun updateLines(update: PackageUpdate): List<String> {
    val (current, replacement) = update
    val before = current.artifact.version
    val after = replacement.artifact.version
    return buildList {
        add("~ ${replacement.name} [${replacement.id.provider}:${replacement.id.value}]")
        add(
            "  ${before.label ?: "unlabelled"} (file ${before.artifactId.value}) -> " +
                "${after.label ?: "unlabelled"} (file ${after.artifactId.value})",
        )
        if (current.dependencies != replacement.dependencies) {
            val beforeIds = current.dependencies.joinToString(", ") { it.value }.ifEmpty { "none" }
            val afterIds = replacement.dependencies.joinToString(", ") { it.value }.ifEmpty { "none" }
            add("  Dependencies: $beforeIds -> $afterIds")
        }
    }
}

private fun profileFailureText(failure: ProfileFailure): String = when (failure) {
    ProfileFailure.MissingProfile -> "No knitty.yaml in this directory. Add a search result to start a profile."
    ProfileFailure.InvalidProfile -> "Invalid knitty.yaml. Check its schema, game, id and unique numeric mod IDs."
    ProfileFailure.InvalidLock -> "Invalid or mismatched knitty.lock. Restore the shared lockfile."
    ProfileFailure.LockOutOfDate -> "Profile and lock differ. Return to search and press l to resolve missing pins."
    ProfileFailure.WrongGame -> "This profile targets a different or unsupported game."
    ProfileFailure.PackageNotInProfile -> "This mod is not explicitly requested. Dependencies are kept until their parent mods are removed."
    ProfileFailure.StalePlan -> "Files changed after planning. Review a fresh plan."
    ProfileFailure.Busy -> "Another operation is in progress. Retry later."
    ProfileFailure.FilesystemFailure -> "Filesystem operation failed. Check free space and permissions."
    ProfileFailure.RecoveryRequired -> "Keep transaction backups and follow docs/profile-slice.md to recover."
    ProfileFailure.DifferentProfile -> "This installation belongs to another profile. Use that profile or a separate installation."
    ProfileFailure.ModifiedInstallation -> "Installed files were modified. Preserve your changes and restore the recorded files before syncing."
    is ProfileFailure.Planning -> planFailureMessage(failure.failure)
    is ProfileFailure.Deployment -> applyFailureMessage(failure.failure)
    is ProfileFailure.Server -> serverFailureMessage(failure.failure)
    is ProfileFailure.ServerStopped -> {
        val cause = profileFailureText(failure.cause)
        "Knitty stopped the server and did not request a restart. Check Pelican. $cause"
    }
}
