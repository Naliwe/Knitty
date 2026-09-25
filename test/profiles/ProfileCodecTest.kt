package knitty.profiles

import knitty.core.model.*
import knitty.sampleServer
import kotlin.test.*

class ProfileCodecTest {
    private val codec = ProfileCodec()
    private val profile = Profile("friends", GameId("core-keeper"), listOf(PackageId("modio", "3177992")))
    private val lock = ProfileLock(
        profile.id, profile.game,
        listOf(
            LockedPackage(
                profile.packages.single(), "CoreLib",
                PackageArtifact(
                    PackageVersion(ArtifactId("8237090"), "5.0.0"), "corelib.zip", 1234,
                    Checksum(ChecksumAlgorithm.MD5, "a".repeat(32)),
                ),
            ),
        ),
    )

    @Test
    fun yamlEmitterPreservesStringsThatLookLikeScalars() {
        for (id in listOf("true", "null", "123", "1e3")) {
            val expected = profile.copy(id = id)

            assertEquals(expected, codec.decodeProfile(codec.encodeProfile(expected)))
        }
    }

    @Test
    @Suppress("HttpUrlsUsage") // This fixture verifies that insecure panel endpoints are rejected.
    fun namedServersRoundTripInProfileAndNeverEnterTheLockfile() {
        val configured = profile.copy(servers = listOf(sampleServer))
        val yaml = codec.encodeProfile(configured)
        assertEquals(configured, codec.decodeProfile(yaml))
        assertContains(yaml, "schemaVersion: 2")
        assertContains(yaml, "hostKey:")
        assertFalse(codec.encodeLock(lock).contains(sampleServer.panelUrl))
        assertTrue(lock.isCompleteFor(configured))

        for (bad in listOf(
            yaml.replace("https://", "http://"),
            yaml.replace("https://", "https://secret@"),
            yaml.replace("2022", "0"),
            yaml.replace("hostKey:", "password:"),
            yaml.replace("SHA256:", "MD5:"),
            yaml.replace("  ovh:", "  ../ovh:"),
            yaml.replace(sampleServer.serverId, "../escape"),
            yaml.replace(sampleServer.panelUrl, "https://[malformed"),
        )) {
            assertEquals(
                ProfileFailure.InvalidProfile,
                assertFailsWith<ProfileFileFailure> { codec.decodeProfile(bad) }.failure,
            )
        }
    }

    @Test
    fun dependencyGraphsRoundTripAndLegacyLocksRemainReadable() {
        val dependency = lock.packages.single().copy(id = PackageId("modio", "9"))
        val dependent = lock.packages.single().copy(dependencies = listOf(dependency.id))
        val graph = lock.copy(packages = listOf(dependency, dependent))
        val encoded = codec.encodeLock(graph)
        assertEquals(graph, codec.decodeLock(encoded))
        assertTrue(graph.isCompleteFor(profile))
        assertFalse(graph.isCompleteFor(profile.copy(packages = listOf(dependency.id))))

        val legacy = codec.encodeLock(lock).replace("\"schemaVersion\": 2", "\"schemaVersion\": 1")
            .replace(Regex(""",\s*"dependencies": \[]"""), "")

        assertEquals(lock, codec.decodeLock(legacy))
        for (bad in listOf(
            graph.copy(packages = listOf(dependent)),
            graph.copy(packages = listOf(dependency.copy(dependencies = listOf(dependent.id)), dependent)),
            graph.copy(
                packages = listOf(
                    dependency,
                    dependent.copy(dependencies = listOf(dependency.id, dependency.id)),
                ),
            ),
        )) {
            assertEquals(
                ProfileFailure.InvalidLock,
                assertFailsWith<ProfileFileFailure> { codec.decodeLock(codec.encodeLock(bad)) }.failure,
            )
        }
    }

    @Test
    fun profileYamlAndLockJsonRoundTripWithoutMachineDetails() {
        val yaml = codec.encodeProfile(profile)
        val json = codec.encodeLock(lock)
        assertEquals(profile, codec.decodeProfile(yaml))
        assertEquals(lock, codec.decodeLock(json))
        assertContains(yaml, "mods:")
        for (forbidden in listOf("/home/", "SteamLibrary", "api_key", "binary_url", "directory")) {
            assertFalse((yaml + json).contains(forbidden))
        }
        assertEquals(
            profile,
            codec.decodeProfile(
                """
            # Shared with the group
            schemaVersion: 1
            id: friends
            game: core-keeper
            mods: [modio:3177992]
        """.trimIndent(),
            ),
        )
    }

    @Test
    fun rejectsAmbiguousYamlAndInvalidOrUnknownLockFields() {
        val yaml = codec.encodeProfile(profile)
        for (bad in listOf(
            yaml + "game: other\n",
            "$yaml  - modio:3177992\n",
            yaml.replace("schemaVersion: 1", "schemaVersion: 2"),
            "!!java.net.URL [https://example.com]",
        )) {
            assertEquals(
                ProfileFailure.InvalidProfile,
                assertFailsWith<ProfileFileFailure> { codec.decodeProfile(bad) }.failure,
            )
        }
        val json = codec.encodeLock(lock)
        for (bad in listOf(
            json.replace("8237090", "../escape"),
            json.replace("1234", "-1"),
            json.replace("\"schemaVersion\": 2", "\"schemaVersion\": 3"),
            json.replace("\"game\":", "\"directory\": \"/home/user\", \"game\":"),
        )) {
            assertEquals(
                ProfileFailure.InvalidLock,
                assertFailsWith<ProfileFileFailure> { codec.decodeLock(bad) }.failure,
            )
        }
    }
}
