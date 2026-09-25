package knitty.providers

import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import knitty.core.application.DefaultPlanInstall
import knitty.core.application.PlanInstallRequest
import knitty.core.model.*
import knitty.core.ports.InstallCandidateOutcome
import knitty.providers.modio.ModioProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class ModioInstallCandidateTest {
    private val game = ProviderGameId("example-game")
    private val reference = PackageReference("modio", "123")
    private val games = """{"data":[{"id":42,"name_id":"example-game"}]}"""
    private val mod = """{
        "id":123,"game_id":42,"name_id":"storage","name":"Storage","summary":"More space","status":1,"visible":1,
        "submitted_by":{"username":"Alice"},"profile_url":"https://mod.io/g/example-game/m/storage",
        "modfile":{"id":456,"version":"old-search-label"}
    }"""
    private val file = """{
        "id":456,"mod_id":123,"filename":"storage.zip","filesize":1024,"version":"1.2",
        "filehash":{"md5":"ABCDEF0123456789ABCDEF0123456789"},"virus_positive":0,
        "download":{"binary_url":"https://cdn.example/storage.zip?temporary-secret=abc","date_expires":1}
    }"""
    private val dependencies = """{"data":[],"result_offset":0,"result_total":0}"""

    @Test
    fun readsEveryDirectDependencyPageAndRejectsIncompleteOrChangingPages() = runTest {
        for (badPage in listOf(
            null,
            """{"data":[],"result_offset":1,"result_total":2}""",
            """{"data":[{"mod_id":10}],"result_offset":0,"result_total":2}""",
            """{"data":[{"mod_id":10}],"result_offset":1,"result_total":3}""",
            """{"data":[{"mod_id":9}],"result_offset":1,"result_total":2}""",
            """{"data":[{"mod_id":0}],"result_offset":1,"result_total":2}""",
        )) {
            val offsets = mutableListOf<String?>()
            HttpClient(
                MockEngine { request ->
                    val body = when {
                        request.url.encodedPath.endsWith("/games") -> games
                        request.url.encodedPath.endsWith("/123") -> mod
                        request.url.encodedPath.endsWith("/files/456") -> file
                        else -> {
                            assertEquals("false", request.url.parameters["recursive"])
                            val offset = request.url.parameters["_offset"]
                            offsets += offset
                            if (offset == "0") {
                                """{"data":[{"mod_id":9}],"result_offset":0,"result_total":2}"""
                            } else if (badPage != null) {
                                badPage
                            } else {
                                """{"data":[{"mod_id":10}],"result_offset":1,"result_total":2}"""
                            }
                        }
                    }
                    respond(body)
                },
            ).use { client ->
                val result = ModioProvider(client, "secret", "https://u-12345.modapi.io/v1").get(game, reference)
                assertEquals(listOf<String?>("0", "1"), offsets)
                if (badPage != null) {
                    assertEquals(
                        InstallCandidateOutcome.Failed(PlanInstallFailure.Provider(ProviderFailure.InvalidResponse)),
                        result,
                    )
                } else {
                    val candidate = assertIs<InstallCandidateOutcome.Found>(result).candidate
                    assertEquals(listOf(PackageId("modio", "9"), PackageId("modio", "10")), candidate.dependencies)
                    assertNotNull(candidate.artifact)
                }
            }
        }
    }

    @Test
    fun privateOrUnacceptedModsCannotProduceInstallCandidates() = runTest {
        for (modBody in listOf(
            mod.replace("\"status\":1", "\"status\":0"),
            mod.replace("\"visible\":1", "\"visible\":0"),
        )) {
            HttpClient(
                MockEngine { request ->
                    respond(
                        when (request.url.encodedPath) {
                            "/v1/games" -> games
                            "/v1/games/42/mods/123" -> modBody
                            else -> error("Must not fetch files or dependencies for an unpublished mod")
                        },
                    )
                },
            ).use { client ->
                val provider = ModioProvider(client, "secret", "https://u-12345.modapi.io/v1")

                assertEquals(
                    InstallCandidateOutcome.Failed(PlanInstallFailure.ArtifactUnavailable),
                    provider.get(game, reference),
                )
            }
        }
    }

    @Test
    fun resolvesNumericAndSlugReferencesUsingOnlyMetadataRequests() = runTest {
        for (value in listOf("123", "storage")) {
            val paths = mutableListOf<String>()
            HttpClient(
                MockEngine { request ->
                    assertEquals(HttpMethod.Get, request.method)
                    assertEquals("u-12345.modapi.io", request.url.host)
                    assertEquals("secret", request.url.parameters["api_key"])
                    paths += request.url.encodedPath

                    val body = when (request.url.encodedPath) {
                        "/v1/games" -> games
                        "/v1/games/42/mods/123" -> mod
                        "/v1/games/42/mods" -> {
                            assertEquals("storage", request.url.parameters["name_id"])
                            """{"data":[$mod],"result_offset":0,"result_total":1}"""
                        }

                        "/v1/games/42/mods/123/dependencies" -> {
                            assertEquals("100", request.url.parameters["_limit"])
                            assertEquals("false", request.url.parameters["recursive"])
                            dependencies
                        }

                        "/v1/games/42/mods/123/files/456" -> file
                        else -> error("Unexpected request: ${request.url.encodedPath}")
                    }
                    respond(body)
                },
            ).use { client ->
                val provider = ModioProvider(client, "secret", "https://u-12345.modapi.io/v1")
                val planner = DefaultPlanInstall(provider, "modio", mapOf(GameId("example") to game))

                val outcome = planner.plan(PlanInstallRequest(GameId("example"), PackageReference("modio", value)))

                val plan = assertIs<PlanInstallOutcome.Planned>(outcome).plan
                val install = assertIs<Change.Install>(plan.changes.single())
                assertEquals(PackageId("modio", "123"), install.id)
                assertEquals(PackageVersion(ArtifactId("456"), "1.2"), install.artifact.version)
                assertEquals("abcdef0123456789abcdef0123456789", install.artifact.checksum?.value)
                assertEquals(1024, plan.downloadBytes)
                assertEquals(4, paths.size)
                assertFalse(plan.toString().contains("temporary-secret"))
                assertFalse(plan.toString().contains("secret"))
            }
        }
    }

    @Test
    fun unpublishedModsDoNotFetchFiles() = runTest {
        val cases = listOf(
            mod.replace("{\"id\":456,\"version\":\"old-search-label\"}", "null") to dependencies,
            mod.replace("{\"id\":456,\"version\":\"old-search-label\"}", "{}") to dependencies,
        )
        for ((modBody, dependencyBody) in cases) {
            HttpClient(
                MockEngine { request ->
                    respond(
                        when {
                            request.url.encodedPath.endsWith("/games") -> games
                            request.url.encodedPath.endsWith("/dependencies") -> dependencyBody
                            request.url.encodedPath.endsWith("/123") -> modBody
                            else -> error("Must not request a file")
                        },
                    )
                },
            ).use { client ->
                val provider = ModioProvider(client, "secret", "https://u-12345.modapi.io/v1")
                val planner = DefaultPlanInstall(provider, "modio", mapOf(GameId("example") to game))

                val result = planner.plan(PlanInstallRequest(GameId("example"), reference))

                val failure = assertIs<PlanInstallOutcome.Failed>(result).failure
                assertEquals(PlanInstallFailure.NoPublishedFile, failure)
            }
        }
    }

    @Test
    fun refusesMismatchedOrInvalidFileMetadata() = runTest {
        val cases = listOf(
            file.replace("\"id\":456", "\"id\":999"),
            file.replace("\"mod_id\":123", "\"mod_id\":999"),
            file.replace("\"filesize\":1024", "\"filesize\":-1"),
            file.replace("ABCDEF0123456789ABCDEF0123456789", "invalid"),
            "{}",
        )
        for (body in cases) {
            val result = lookupWithFile(body)

            assertEquals(
                InstallCandidateOutcome.Failed(PlanInstallFailure.Provider(ProviderFailure.InvalidResponse)),
                result,
            )
        }
    }

    @Test
    fun unavailableDownloadsAndFlaggedFilesAreExplicit() = runTest {
        for (body in listOf(
            file.replace("https://cdn", "http://cdn"),
            file.replace("\"virus_positive\":0", "\"virus_positive\":1"),
        )) {
            assertEquals(InstallCandidateOutcome.Failed(PlanInstallFailure.ArtifactUnavailable), lookupWithFile(body))
        }
    }

    @Test
    fun missingLabelsAndChecksumsDoNotInventValues() = runTest {
        val body = file.replace("\"version\":\"1.2\"", "\"version\":null")
            .replace("{\"md5\":\"ABCDEF0123456789ABCDEF0123456789\"}", "null")

        val outcome = assertIs<InstallCandidateOutcome.Found>(lookupWithFile(body))

        assertNull(outcome.candidate.artifact?.version?.label)
        assertNull(outcome.candidate.artifact?.checksum)
        assertEquals(ArtifactId("456"), outcome.candidate.artifact?.version?.artifactId)
    }

    @Test
    fun missingPackagesMalformedReferencesAndCancellationAreHandled() = runTest {
        HttpClient(
            MockEngine { request ->
                if (request.url.encodedPath.endsWith("/games")) respond(games)
                else respond("not found", HttpStatusCode.NotFound)
            },
        ).use { client ->
            val provider = ModioProvider(client, "secret", "https://u-12345.modapi.io/v1")

            assertEquals(
                InstallCandidateOutcome.Failed(PlanInstallFailure.Provider(ProviderFailure.PackageNotFound)),
                provider.get(game, reference),
            )
            assertEquals(
                InstallCandidateOutcome.Failed(PlanInstallFailure.InvalidPackageReference),
                provider.get(game, PackageReference("modio", "../../anything")),
            )
        }

        HttpClient(MockEngine { throw CancellationException() }).use { client ->
            val provider = ModioProvider(client, "secret", "https://u-12345.modapi.io/v1")
            assertFailsWith<CancellationException> { provider.get(game, reference) }
        }
    }

    private suspend fun lookupWithFile(body: String): InstallCandidateOutcome {
        HttpClient(
            MockEngine { request ->
                respond(
                    when {
                        request.url.encodedPath.endsWith("/games") -> games
                        request.url.encodedPath.endsWith("/dependencies") -> dependencies
                        request.url.encodedPath.endsWith("/files/456") -> body
                        request.url.encodedPath.endsWith("/123") -> mod
                        else -> error("Unexpected request")
                    },
                )
            },
        ).use { client ->
            return ModioProvider(client, "secret", "https://u-12345.modapi.io/v1").get(game, reference)
        }
    }
}
