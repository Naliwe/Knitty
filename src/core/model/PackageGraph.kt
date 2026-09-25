package knitty.core.model

sealed interface PackageGraphOutcome {
    data class Ordered(val packages: List<LockedPackage>) : PackageGraphOutcome
    data class Missing(val id: PackageId) : PackageGraphOutcome
    data class Cycle(val packages: List<PackageId>) : PackageGraphOutcome
    data object Invalid : PackageGraphOutcome
}

// A bounded dependency graph, with one exact pin per package; no version-range solving.
fun orderPackages(roots: List<PackageId>, packages: List<LockedPackage>): PackageGraphOutcome {
    val byId = packages.associateBy { it.id }
    if (packages.size > 500 || byId.size != packages.size || roots.distinct().size != roots.size) {
        return PackageGraphOutcome.Invalid
    }

    val ordered = linkedMapOf<PackageId, LockedPackage>()
    val path = mutableListOf<PackageId>()

    fun visit(id: PackageId): PackageGraphOutcome {
        if (id in ordered) return PackageGraphOutcome.Ordered(emptyList())
        if (id in path) return PackageGraphOutcome.Cycle(path.dropWhile { it != id } + id)
        val item = byId[id]
            ?: return PackageGraphOutcome.Missing(id)
        if (item.dependencies.distinct().size != item.dependencies.size) return PackageGraphOutcome.Invalid

        path += id
        for (dependency in item.dependencies) {
            when (val result = visit(dependency)) {
                is PackageGraphOutcome.Ordered -> Unit
                is PackageGraphOutcome.Missing, is PackageGraphOutcome.Cycle, PackageGraphOutcome.Invalid -> return result
            }
        }

        path.removeAt(path.lastIndex)
        ordered[id] = item
        return PackageGraphOutcome.Ordered(emptyList())
    }

    for (root in roots) {
        when (val result = visit(root)) {
            is PackageGraphOutcome.Ordered -> Unit
            is PackageGraphOutcome.Missing, is PackageGraphOutcome.Cycle, PackageGraphOutcome.Invalid -> return result
        }
    }

    return PackageGraphOutcome.Ordered(ordered.values.toList())
}

fun ProfileLock.isCompleteFor(profile: Profile): Boolean {
    val matchesProfile = profileId == profile.id && game == profile.game

    return matchesProfile && when (val graph = orderPackages(profile.packages, packages)) {
        is PackageGraphOutcome.Ordered -> graph.packages.size == packages.size
        is PackageGraphOutcome.Missing, is PackageGraphOutcome.Cycle, PackageGraphOutcome.Invalid -> false
    }
}
