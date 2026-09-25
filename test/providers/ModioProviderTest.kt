package knitty.providers

import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import knitty.core.model.*
import knitty.providers.modio.ModioProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.test.*

class ModioProviderTest {
    private val game = ProviderGameId("example-game")
    private val games = """{"data":[{"id":42,"name_id":"example-game"}]}"""
    private val mods = """{
        "data":[
          {"id":123,"game_id":42,"name_id":"storage","name":"Storage 箱","summary":"Store more",
           "submitted_by":{"username":"modder"},"profile_url":"https://mod.io/g/example-game/m/storage",
           "modfile":{"id":456,"version":"1.2.0"},"unknown_future_field":true},
          {"id":124,"game_id":42,"name_id":"boxes","name":"Boxes","summary":"More boxes",
           "submitted_by":{"username":"other"},"profile_url":"https://mod.io/g/example-game/m/boxes","modfile":null}
        ],"result_offset":20,"result_total":23,"result_count":2,"result_limit":20
    }"""

    @Test
    fun encodesRequestsAndMapsResultsWithPaginationAndMissingFile() = runTest {
        val engine = MockEngine { request ->
            assertEquals("secret", request.url.parameters["api_key"])
            assertEquals("u-12345.modapi.io", request.url.host)
            assertEquals(HttpMethod.Get, request.method)
            when (request.url.encodedPath) {
                "/v1/games" -> {
                    assertEquals(game.value, request.url.parameters["name_id"])
                    respond(games)
                }

                "/v1/games/42/mods" -> {
                    assertEquals("storage & 箱", request.url.parameters["_q"])
                    assertEquals("20", request.url.parameters["_offset"])
                    assertEquals("20", request.url.parameters["_limit"])
                    assertEquals("name", request.url.parameters["_sort"])
                    respond(mods)
                }

                else -> error("Unexpected path")
            }
        }
        HttpClient(engine).use { client ->
            val result = assertIs<SearchOutcome.Found>(
                ModioProvider(client, "secret", "https://u-12345.modapi.io/v1").search(
                    game,
                    "storage & 箱",
                    20,
                ),
            )
            assertEquals(2, engine.requestHistory.size)
            assertEquals(23, result.page.total)
            assertEquals(22, result.page.nextOffset)
            assertEquals(
                ModSearchResult(
                    PackageId("modio", "123"), "storage", "Storage 箱", "Store more",
                    "modder", "1.2.0", "https://mod.io/g/example-game/m/storage",
                ),
                result.page.mods.first(),
            )
            assertNull(result.page.mods.last().version)
        }
    }

    @Test
    fun missingKeyDoesNotSendARequest() = runTest {
        HttpClient(MockEngine { error("No request expected") }).use { client ->
            assertEquals(
                SearchOutcome.Failed(SearchFailure.Provider("modio", ProviderFailure.MissingCredentials)),
                ModioProvider(client, " ", "https://u-12345.modapi.io/v1").search(game, "storage", 0),
            )
        }
    }

    @Test
    @Suppress("HttpUrlsUsage") // This fixture verifies that insecure endpoints never receive credentials.
    fun missingOrInvalidApiPathDoesNotSendCredentials() = runTest {
        val invalidPaths = listOf(
            null, "", "https://api.mod.io/v1", "http://u-12345.modapi.io/v1",
            "https://u-12345.modapi.io.example.com/v1", "https://u-12345.modapi.io/v1?extra=value",
        )
        HttpClient(MockEngine { error("No request expected") }).use { client ->
            for (path in invalidPaths) {
                assertEquals(
                    SearchOutcome.Failed(SearchFailure.Provider("modio", ProviderFailure.InvalidEndpoint)),
                    ModioProvider(client, "secret", path).search(game, "storage", 0),
                )
            }
        }
    }

    @Test
    fun acceptsUserAndGamePathsInProductionAndSandbox() = runTest {
        for (path in listOf(
            "https://u-12345.modapi.io/v1/", "https://g-42.modapi.io/v1",
            "https://u-12345.test.mod.io/v1", "https://g-42.test.mod.io/v1/",
        )) {
            HttpClient(
                MockEngine { request ->
                    assertEquals(path.trimEnd('/') + "/games", request.url.toString().substringBefore('?'))
                    respond("""{"data":[]}""")
                },
            ).use { client ->
                assertEquals(
                    SearchOutcome.Failed(SearchFailure.Provider("modio", ProviderFailure.GameNotFound)),
                    ModioProvider(client, "secret", path).search(game, "storage", 0),
                )
            }
        }
    }

    @Test
    fun mapsHttpErrorsWithoutLeakingResponseOrCredentials() = runTest {
        val cases = mapOf(
            401 to SearchFailure.Provider("modio", ProviderFailure.AccessDenied),
            403 to SearchFailure.Provider("modio", ProviderFailure.AccessDenied),
            404 to SearchFailure.Provider("modio", ProviderFailure.GameNotFound),
            429 to SearchFailure.Provider("modio", ProviderFailure.RateLimited(12)),
            500 to SearchFailure.Provider("modio", ProviderFailure.Unavailable),
            302 to SearchFailure.Provider("modio", ProviderFailure.Unavailable),
        )
        for ((status, failure) in cases) {
            HttpClient(
                MockEngine {
                    respond(
                        "secret provider details",
                        HttpStatusCode.fromValue(status),
                        headersOf(HttpHeaders.RetryAfter, "12"),
                    )
                },
            ) { followRedirects = false }.use { client ->
                assertEquals(
                    SearchOutcome.Failed(failure),
                    ModioProvider(client, "secret", "https://u-12345.modapi.io/v1").search(game, "x", 0),
                )
            }
        }
    }

    @Test
    fun malformedResponsesFailExplicitly() = runTest {
        for (body in listOf(
            "not json", "{}", mods.replace("\"game_id\":42", "\"game_id\":99"),
            mods.replace("\"result_offset\":20", "\"result_offset\":0"),
        )) {
            HttpClient(
                MockEngine { request ->
                    respond(if (request.url.encodedPath.endsWith("/games")) games else body)
                },
            ).use { client ->
                assertEquals(
                    SearchOutcome.Failed(SearchFailure.Provider("modio", ProviderFailure.InvalidResponse)),
                    ModioProvider(client, "secret", "https://u-12345.modapi.io/v1").search(game, "x", 20),
                )
            }
        }
    }

    @Test
    fun emptyPageIsSuccessfulAndHasNoNextPage() = runTest {
        HttpClient(
            MockEngine { request ->
                respond(
                    if (request.url.encodedPath.endsWith("/games")) games
                    else """{"data":[],"result_offset":0,"result_total":0}""",
                )
            },
        ).use { client ->
            val result = assertIs<SearchOutcome.Found>(
                ModioProvider(client, "secret", "https://u-12345.modapi.io/v1").search(
                    game,
                    "nothing",
                    0,
                ),
            )
            assertTrue(result.page.mods.isEmpty())
            assertNull(result.page.nextOffset)
        }
    }

    @Test
    fun absentGameIsExplicit() = runTest {
        HttpClient(MockEngine { respond("""{"data":[]}""") }).use { client ->
            assertEquals(
                SearchOutcome.Failed(SearchFailure.Provider("modio", ProviderFailure.GameNotFound)),
                ModioProvider(client, "secret", "https://u-12345.modapi.io/v1").search(game, "x", 0),
            )
        }
    }

    @Test
    fun networkFailureIsExplicitAndCancellationPropagates() = runTest {
        HttpClient(MockEngine { throw IOException("URL includes secret") }).use { client ->
            assertEquals(
                SearchOutcome.Failed(SearchFailure.Provider("modio", ProviderFailure.Unavailable)),
                ModioProvider(client, "secret", "https://u-12345.modapi.io/v1").search(game, "x", 0),
            )
        }
        HttpClient(MockEngine { throw CancellationException("cancelled") }).use { client ->
            assertFailsWith<CancellationException> {
                ModioProvider(
                    client,
                    "secret",
                    "https://u-12345.modapi.io/v1",
                ).search(game, "x", 0)
            }
        }
    }
}
