package knitty.core

import knitty.core.application.DefaultPlanInstall
import knitty.core.application.PlanInstallRequest
import knitty.core.model.*
import knitty.core.ports.InstallCandidateOutcome
import knitty.profileGame
import knitty.profilePackage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class DependencyResolutionTest {
    @Test
    fun traversesTransitiveDependenciesOnceAndOrdersThemBeforeDependents() = runTest {
        val edges = mapOf("1" to listOf("2", "3"), "2" to listOf("4"), "3" to listOf("4"))
        val calls = mutableListOf<String>()
        val planner = planner { reference ->
            calls += reference.value
            val id = reference.value.takeUnless { it == "root" } ?: "1"
            candidate(id, edges[id].orEmpty())
        }

        val plan = assertIs<PlanInstallOutcome.Planned>(planner.plan(request("root"))).plan

        assertEquals(PackageId("modio", "1"), plan.requested)
        assertEquals(listOf("4", "2", "3", "1"), plan.changes.map { (it as Change.Install).id.value })
        assertEquals(listOf("root", "2", "3", "4"), calls)
        assertEquals(12, plan.downloadBytes)
    }

    @Test
    fun pinnedDependenciesStayPinnedAndSlugReaddsPreserveExistingGraph() = runTest {
        val pinned = profilePackage.copy(dependencies = listOf(PackageId("modio", "2")))
        val dependency = profilePackage.copy(id = PackageId("modio", "2"))
        val calls = mutableListOf<String>()
        val planner = planner { reference ->
            calls += reference.value
            candidate("1", listOf("3"))
        }

        val result = assertIs<PlanInstallOutcome.Planned>(
            planner.plan(request("root").copy(pins = listOf(pinned, dependency))),
        ).plan

        assertEquals(listOf(dependency.install(), pinned.install()), result.changes)
        assertEquals(listOf("root"), calls)
    }

    @Test
    fun missingDependencyReportsBothPackagesAndDoesNotReturnPartialPlan() = runTest {
        val planner = planner { reference ->
            if (reference.value == "1") candidate("1", listOf("2"))
            else InstallCandidateOutcome.Failed(PlanInstallFailure.Provider(ProviderFailure.PackageNotFound))
        }

        val failure = assertIs<PlanInstallOutcome.Failed>(planner.plan(request("1"))).failure

        assertEquals(
            PlanInstallFailure.DependencyUnavailable(
                PackageId("modio", "1"), PackageId("modio", "2"),
                PlanInstallFailure.Provider(ProviderFailure.PackageNotFound),
            ),
            failure,
        )
    }

    @Test
    fun rejectsCyclesAndOversizedGraphsAndPropagatesCancellation() = runTest {
        val cyclic = planner { candidate(it.value, listOf(if (it.value == "1") "2" else "1")) }
        val failure = assertIs<PlanInstallOutcome.Failed>(cyclic.plan(request("1"))).failure
        assertEquals(
            PlanInstallFailure.DependencyCycle(listOf("1", "2", "1").map { PackageId("modio", it) }),
            failure,
        )

        val oversized = planner { candidate(it.value, listOf((it.value.toInt() + 1).toString())) }
        assertEquals(
            PlanInstallOutcome.Failed(PlanInstallFailure.DependencyLimitExceeded),
            oversized.plan(request("1")),
        )
        val cancelled = planner { throw CancellationException() }
        assertFailsWith<CancellationException> { cancelled.plan(request("1")) }
    }

    private fun request(value: String) = PlanInstallRequest(profileGame, PackageReference("modio", value))

    private fun candidate(id: String, dependencies: List<String>) = InstallCandidateOutcome.Found(
        InstallCandidate(
            PackageId("modio", id), "Mod $id", profilePackage.artifact,
            dependencies.map { PackageId("modio", it) },
        ),
    )

    private fun planner(get: suspend (PackageReference) -> InstallCandidateOutcome) = DefaultPlanInstall(
        { _, reference -> get(reference) }, "modio", mapOf(profileGame to ProviderGameId("corekeeper")),
    )
}
