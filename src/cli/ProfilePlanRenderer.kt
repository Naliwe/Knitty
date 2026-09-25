package knitty.cli

import knitty.core.model.*
import knitty.terminal.displaySize
import knitty.terminal.plainText

internal fun renderProfileEdit(plan: ProfileEditPlan): List<String> = buildList {
    add(CliStyle.heading("Profile plan · ${plan.profile.game.value} · ${plan.profile.id}"))
    add("")
    addAll(renderProfilePackages(plan, includeKept = true))
    add(CliStyle.muted("Save desired state and exact pins; deploy with sync."))
}

internal fun renderUpdates(plan: ProfileEditPlan): List<String> = buildList {
    add(CliStyle.heading("Published release changes · ${plan.profile.game.value}"))
    if (!plan.hasChanges) {
        add("All locked packages match their current published files.")
    } else {
        add("")
        addAll(renderProfilePackages(plan, includeKept = false))
        add(CliStyle.muted("Provider-selected releases; version order and game compatibility are not verified."))
    }
}

private fun renderProfilePackages(plan: ProfileEditPlan, includeKept: Boolean): List<String> = buildList {
    val previous = plan.before.lock?.packages.orEmpty().map { it.id }.toSet()
    val updates = plan.updates.associateBy { it.replacement.id }
    for (item in plan.lock.packages) {
        val role = if (item.id in plan.profile.packages) "" else " · dependency"
        val update = updates[item.id]
        if (update != null) {
            addAll(renderUpdate(update))
            add(CliStyle.muted("    ${if (role.isEmpty()) "requested" else "dependency"}"))
        } else if (item.id !in previous || includeKept) {
            val symbol = if (item.id in previous) "=" else "+"
            add(CliStyle.change(symbol, "${item.name}  ${item.artifact.version.label ?: "unlabelled"}$role"))
            add(CliStyle.muted("    ${item.id.provider}:${item.id.value} · file ${item.artifact.version.artifactId.value}"))
        }
        if (item.dependencies.isNotEmpty() && (update != null || item.id !in previous || includeKept)) {
            add(CliStyle.muted("    Requires: ${dependencyNames(item)}"))
        }
    }
    plan.removals.forEach { add(CliStyle.change("-", "${it.name} [${it.id.provider}:${it.id.value}]")) }
    add("")
    add(CliStyle.heading("${plan.additions.size} add · ${updates.size} update · ${plan.removals.size} remove"))
    val downloads =
        plan.additions + plan.updates.filter { it.current.artifact != it.replacement.artifact }.map { it.replacement }
    add("  Download: ${CliStyle.version(displaySize(downloads.sumOf { it.artifact.sizeBytes }))}")
}

private fun dependencyNames(item: LockedPackage): String =
    item.dependencies.joinToString(", ") { "${it.provider}:${it.value}" }.ifEmpty { "none" }

private fun renderUpdate(update: PackageUpdate): List<String> {
    val (current, replacement) = update
    val before = current.artifact.version
    val after = replacement.artifact.version
    return buildList {
        add(CliStyle.change("~", "${replacement.name} [${replacement.id.provider}:${replacement.id.value}]"))
        add(
            "    ${plainText(before.label ?: "unlabelled")} (file ${before.artifactId.value}) -> " +
                "${CliStyle.version(after.label ?: "unlabelled")} (file ${after.artifactId.value})",
        )
        if (current.dependencies != replacement.dependencies) {
            add(CliStyle.muted("    Dependencies: ${dependencyNames(current)} -> ${dependencyNames(replacement)}"))
        }
    }
}

internal fun renderSyncPlan(plan: SyncPlan): List<String> = buildList {
    add(CliStyle.heading("Sync plan · ${plan.installation.game.value}"))
    add(CliStyle.muted("Mods directory: ${plan.installation.modsDirectory}"))
    plan.server?.let { server ->
        add(CliStyle.heading("Server: ${server.target.name} · ${server.target.panelUrl} · ${server.target.serverId}"))
        add(CliStyle.muted("SFTP: ${server.target.sftp.host}:${server.target.sftp.port}"))
        add(
            if (server.before.power == ServerPowerState.Running) {
                "Deployment will stop the server and restart it after verification."
            } else {
                "The server is offline and will remain offline."
            },
        )
        if (server.before.autoUpdate) add("Pelican auto-update is enabled: starting may update the game executable.")
    }
    add("")

    plan.remove.forEach { item ->
        add(CliStyle.change("-", "${item.name} [${item.id.provider}:${item.id.value}] · file ${item.artifactId.value}"))
    }
    plan.install.forEach { item ->
        val role = if (item.id in plan.source.profile?.packages.orEmpty()) "" else " · dependency"
        add(CliStyle.change("+", "${item.name}  ${item.artifact.version.label ?: "unlabelled"}$role"))
        add(CliStyle.muted("    ${item.id.provider}:${item.id.value} · file ${item.artifact.version.artifactId.value}"))
    }
    plan.keep.forEach { item ->
        val action = if (item in plan.adopt) "Adopt into profile:" else "="
        add(CliStyle.change(action, "${item.name} · file ${item.artifactId.value}"))
    }
    plan.untouched.forEach { add(CliStyle.muted("  Outside profile, preserved: $it")) }

    add("")
    add(CliStyle.heading("${plan.install.size} install · ${plan.remove.size} remove · ${plan.keep.size} keep"))
    add("  Download: ${CliStyle.version(displaySize(plan.install.sumOf { it.artifact.sizeBytes }))}")
    if (plan.server == null) add(CliStyle.muted("Close Core Keeper before applying."))
    add(CliStyle.muted("Game-version compatibility is not verified."))
}
