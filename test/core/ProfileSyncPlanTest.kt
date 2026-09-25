package knitty.core

import knitty.core.model.*
import knitty.profileGame
import knitty.profilePackage
import knitty.sampleSync
import knitty.updatedPackage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ProfileSyncPlanTest {
    @Test
    fun keepsExactStandalonePinsAndMakesAdoptionExplicit() {
        val installed = InstalledPackage(profilePackage.id, profilePackage.artifact.version.artifactId, "Example")
        val before = sampleSync.before.copy(packages = listOf(installed))

        val result = planProfileSync(sampleSync.source, sampleSync.installation, before)
        val plan = assertIs<ProfileResult.Success<SyncPlan>>(result).value

        assertTrue(plan.install.isEmpty())
        assertTrue(plan.remove.isEmpty())
        assertEquals(listOf(installed), plan.keep)
        assertEquals(listOf(installed), plan.adopt)
        assertEquals(listOf("manual"), plan.untouched)
    }

    @Test
    fun refusesToReplaceAStandaloneModEvenWhenItsPackageIsRequested() {
        val installed = InstalledPackage(profilePackage.id, ArtifactId("older"), "Example")
        val before = sampleSync.before.copy(packages = listOf(installed))

        val result = planProfileSync(sampleSync.source, sampleSync.installation, before)

        assertEquals(ProfileResult.Failed(ProfileFailure.Deployment(ApplyFailure.DeploymentConflict)), result)
    }

    @Test
    fun replacesManagedPinsAndPreservesUnrelatedStandaloneMods() {
        val installed = InstalledPackage(profilePackage.id, profilePackage.artifact.version.artifactId, "Example")
        val unrelated = installed.copy(id = PackageId("modio", "other"), name = "Other")
        val before = sampleSync.before.copy(packages = listOf(installed, unrelated), managed = setOf(installed.id))
        val source = sampleSync.source.copy(lock = sampleSync.source.lock!!.copy(packages = listOf(updatedPackage)))

        val result = planProfileSync(source, sampleSync.installation, before)
        val plan = assertIs<ProfileResult.Success<SyncPlan>>(result).value

        assertEquals(listOf(updatedPackage), plan.install)
        assertEquals(listOf(installed), plan.remove)
        assertTrue(plan.keep.isEmpty())
        assertEquals(listOf("manual", "Other"), plan.untouched)
    }

    @Test
    fun rejectsMissingOrMismatchedStateWithoutConstructingAPlan() {
        val cases = listOf(
            sampleSync.source.copy(profile = null) to ProfileFailure.MissingProfile,
            sampleSync.source.copy(lock = null) to ProfileFailure.LockOutOfDate,
            sampleSync.source.copy(lock = sampleSync.source.lock!!.copy(game = GameId("another"))) to ProfileFailure.LockOutOfDate,
            sampleSync.source.copy(profile = sampleSync.source.profile!!.copy(game = GameId("another"))) to ProfileFailure.WrongGame,
        )

        assertEquals(profileGame, sampleSync.installation.game)
        for ((source, failure) in cases) {
            assertEquals(
                ProfileResult.Failed(failure),
                planProfileSync(source, sampleSync.installation, sampleSync.before),
            )
        }
    }
}
