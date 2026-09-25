package knitty.providers

import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import knitty.core.model.*
import knitty.providers.modio.ModioProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class ModioDownloadTest {
    private val game = ProviderGameId("example-game")
    private val change = Change.Install(
        PackageId("modio", "123"), "Mod",
        PackageArtifact(PackageVersion(ArtifactId("456"), "1"), "mod.zip", 3, null),
    )
    private val games = """{"data":[{"id":42,"name_id":"example-game"}]}"""
    private val mod = """{"id":123,"game_id":42,"name_id":"mod","name":"Mod","summary":"","status":1,"visible":1,
        "submitted_by":{"username":"Alice"},"profile_url":"url","modfile":{"id":999}}"""
    private val file = """{"id":456,"mod_id":123,"filename":"mod.zip","filesize":3,"virus_positive":0,
        "download":{"binary_url":"https://cdn.example/mod.zip?token=temporary"}}"""

    @Test
    fun reviewedDependenciesAllowThePinnedArchiveToDownload() = runTest {
        val reviewed = change.copy(dependencies = listOf(PackageId("modio", "5")))
        HttpClient(
            MockEngine { request ->
                when {
                    request.url.host != "u-12345.modapi.io" -> respond(byteArrayOf(1, 2, 3))
                    request.url.encodedPath.endsWith("/dependencies") ->
                        respond("""{"data":[{"mod_id":5}],"result_offset":0,"result_total":1}""")

                    else -> respond(metadata(request.url.encodedPath))
                }
            },
        ).use { client ->
            var count = 0
            val result = provider(client).download(game, reviewed) { _, size -> count += size }
            assertEquals(DownloadOutcome.Downloaded, result)
            assertEquals(3, count)
        }
    }

    @Test
    fun refreshesPinnedFileAndStreamsAcrossHttpsRedirectWithoutApiCredentials() = runTest {
        val paths = mutableListOf<String>()
        val received = mutableListOf<Byte>()
        HttpClient(
            MockEngine { request ->
                paths += request.url.encodedPath
                if (request.url.host == "u-12345.modapi.io") {
                    assertEquals("secret", request.url.parameters["api_key"])
                    respond(metadata(request.url.encodedPath))
                } else {
                    assertNull(request.url.parameters["api_key"])
                    assertNull(request.headers["Authorization"])
                    if (request.url.host == "cdn.example") {
                        respond("", HttpStatusCode.Found, headersOf("Location", "https://download.example/artifact"))
                    } else {
                        respond(byteArrayOf(1, 2, 3))
                    }
                }
            },
        ) { followRedirects = false }.use { client ->
            val result = provider(client).download(game, change) { bytes, count ->
                received += bytes.take(count)
            }
            assertEquals(DownloadOutcome.Downloaded, result)
            assertEquals(listOf<Byte>(1, 2, 3), received)
            assertContains(paths, "/v1/games/42/mods/123/files/456")
            assertFalse(paths.any { it.endsWith("999") })
        }
    }

    @Test
    fun changedFileOrNewDependenciesPreventBinaryDownload() = runTest {
        for ((replacement, failure) in listOf(
            file.replace("\"filesize\":3", "\"filesize\":4") to ApplyFailure.ArtifactChanged,
            file.replace("\"virus_positive\":0", "\"virus_positive\":1") to ApplyFailure.ArtifactChanged,
            "dependencies" to ApplyFailure.DependenciesChanged,
        )) {
            HttpClient(
                MockEngine { request ->
                    assertEquals("u-12345.modapi.io", request.url.host)
                    val path = request.url.encodedPath
                    respond(
                        when {
                            path.endsWith("/dependencies") && replacement == "dependencies" -> """{"data":[{"mod_id":5}],"result_offset":0,"result_total":1}"""
                            path.endsWith("/files/456") -> replacement
                            else -> metadata(path)
                        },
                    )
                },
            ).use { client ->
                assertEquals(
                    DownloadOutcome.Failed(failure),
                    provider(client).download(game, change) { _, _ -> error("No bytes expected") },
                )
            }
        }
    }

    @Test
    fun refusesRedirectDowngradeAndReportsHttpFailureWithoutLeakingUrls() = runTest {
        for (redirect in listOf(true, false)) {
            HttpClient(
                MockEngine { request ->
                    if (request.url.host == "u-12345.modapi.io") respond(metadata(request.url.encodedPath))
                    else {
                        assertEquals("https", request.url.protocol.name)
                        if (redirect) respond(
                            "",
                            HttpStatusCode.Found,
                            headersOf("Location", "http://insecure.example/file"),
                        )
                        else respond("temporary-token=secret", HttpStatusCode.Forbidden)
                    }
                },
            ) { followRedirects = false }.use { client ->
                val result =
                    provider(client).download(game, change) { _, _ -> error("No bytes expected") }
                assertEquals(DownloadOutcome.Failed(ApplyFailure.DownloadFailed), result)
                assertFalse(result.toString().contains("secret"))
            }
        }
    }

    @Test
    fun cancellationDuringStreamingPropagates() = runTest {
        HttpClient(
            MockEngine { request ->
                if (request.url.host == "u-12345.modapi.io") respond(metadata(request.url.encodedPath))
                else respond(byteArrayOf(1, 2, 3))
            },
        ).use { client ->
            assertFailsWith<CancellationException> {
                provider(client).download(game, change) { _, _ -> throw CancellationException() }
            }
        }
    }

    private fun metadata(path: String): String = when {
        path.endsWith("/games") -> games
        path.endsWith("/dependencies") -> """{"data":[],"result_offset":0,"result_total":0}"""
        path.endsWith("/files/456") -> file
        path.endsWith("/123") -> mod
        else -> error("Unexpected metadata request")
    }

    private fun provider(client: HttpClient) = ModioProvider(client, "secret", "https://u-12345.modapi.io/v1")
}
