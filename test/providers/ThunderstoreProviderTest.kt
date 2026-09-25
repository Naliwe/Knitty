package knitty.providers

import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.ktor.utils.io.*
import knitty.core.model.*
import knitty.providers.thunderstore.ThunderstoreProvider
import kotlinx.coroutines.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.test.*
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

class ThunderstoreProviderTest {
    private val game = ProviderGameId("example-game")

    @Test
    fun streamsCatalogLargerThanTheOriginalBufferLimit() = runTest {
        val padding = ByteArray(33 * 1024 * 1024) { ' '.code.toByte() }
        val body = "[".encodeToByteArray() + padding + "${entry("Example")}]".encodeToByteArray()
        HttpClient(MockEngine { respond(body) }).use { client ->
            val result = ThunderstoreProvider(client).search(game, "Example", 0).page()

            assertEquals("Author-Example", result.mods.single().id.value)
        }
    }

    @Test
    fun cancellingWhileWaitingForBodyBytesReleasesTheCatalogForRetry() = runTest {
        val started = CompletableDeferred<Unit>()
        val body = ByteChannel(autoFlush = true)
        var requests = 0
        HttpClient(
            MockEngine {
                requests++
                if (requests == 1) {
                    body.writeFully("[".encodeToByteArray())
                    started.complete(Unit)
                    respond(body)
                } else {
                    respond("[]")
                }
            },
        ).use { client ->
            val provider = ThunderstoreProvider(client)
            withContext(Dispatchers.Default) {
                val search = async { provider.search(game, "x", 0) }
                started.await()
                delay(50.milliseconds)

                withTimeout(5.seconds) { search.cancelAndJoin() }
                assertTrue(search.isCancelled)
            }

            assertEquals(0, provider.search(game, "x", 0).page().total)
            assertEquals(2, requests)
        }
    }

    @Test
    fun mapsPublishedVersionsAndUsesPublicCommunityCatalogWithoutCredentials() = runTest {
        val body = """[{
            "name":"Storage_Boxes","owner":"Some-Team","full_name":"Some-Team-Storage_Boxes",
            "is_deprecated":false,"ignored":"future field",
            "versions":[
                {"version_number":"9.0.0","description":"inactive","is_active":false},
                {"version_number":"2.0.0","description":"More storage 箱","is_active":true},
                {"version_number":"1.0.0","description":"old","is_active":true}
            ]
        }]"""
        HttpClient(
            MockEngine { request ->
                assertEquals(HttpMethod.Get, request.method)
                assertEquals("https://thunderstore.io/c/example-game/api/v1/package/", request.url.toString())
                assertNull(request.headers[HttpHeaders.Authorization])
                assertNull(request.url.parameters["api_key"])
                respond(body)
            },
        ).use { client ->
            val page = ThunderstoreProvider(client).search(game, "箱", 0).page()

            assertEquals(
                listOf(
                    ModSearchResult(
                        PackageId("thunderstore", "Some-Team-Storage_Boxes"),
                        "Storage_Boxes",
                        "Storage_Boxes",
                        "More storage 箱",
                        "Some-Team",
                        "2.0.0",
                        "https://thunderstore.io/c/example-game/p/Some-Team/Storage_Boxes/",
                    ),
                ),
                page.mods,
            )
            assertEquals(1, page.total)
            assertNull(page.nextOffset)
        }
    }

    @Test
    fun cachesCatalogForQueriesAndDeterministicPaginationExcludingDeprecatedAndInactivePackages() = runTest {
        val packages = (24 downTo 0).map { entry("Storage_${it.toString().padStart(2, '0')}") } + listOf(
            entry("Storage_Deprecated").replace("\"is_deprecated\":false", "\"is_deprecated\":true"),
            entry("Storage_Inactive").replace("\"is_active\":true", "\"is_active\":false"),
        )
        var requests = 0
        HttpClient(
            MockEngine {
                requests++
                respond(packages.joinToString(prefix = "[", postfix = "]"))
            },
        ).use { client ->
            val provider = ThunderstoreProvider(client)
            val first = provider.search(game, "storage", 0).page()
            val second = provider.search(game, "STORAGE", first.nextOffset!!).page()
            val owner = provider.search(game, "author", 0).page()
            val none = provider.search(game, "missing", 0).page()
            val beyond = provider.search(game, "storage", Int.MAX_VALUE).page()

            assertEquals(25, first.total)
            assertEquals(20, first.mods.size)
            assertEquals("Storage_00", first.mods.first().name)
            assertEquals(20, second.offset)
            assertEquals(5, second.mods.size)
            assertEquals("Storage_20", second.mods.first().name)
            assertNull(second.nextOffset)
            assertEquals(25, owner.total)
            assertEquals(0, none.total)
            assertTrue(beyond.mods.isEmpty())
            assertNull(beyond.nextOffset)
            assertEquals(1, requests)
        }
    }

    @Test
    fun expiredCatalogRefreshFailureIsReportedAndCanRecover() = runTest {
        val time = TestTimeSource()
        var requests = 0
        HttpClient(
            MockEngine {
                requests++
                when (requests) {
                    1 -> respond("[${entry("Old")}]")
                    2 -> respond("unavailable", HttpStatusCode.ServiceUnavailable)
                    else -> respond("[${entry("New")}]")
                }
            },
        ).use { client ->
            val provider = ThunderstoreProvider(client, time)
            assertEquals("Old", provider.search(game, "Author", 0).page().mods.single().name)
            time += 4.minutes
            assertEquals("Old", provider.search(game, "Author", 0).page().mods.single().name)
            assertEquals(1, requests)

            time += 1.minutes
            assertEquals(failed(ProviderFailure.Unavailable), provider.search(game, "Author", 0))
            assertEquals("New", provider.search(game, "Author", 0).page().mods.single().name)
            assertEquals(3, requests)
        }
    }

    @Test
    fun concurrentSearchesShareFetchAndCommunitiesNeverShareCatalogs() = runTest {
        val paths = mutableListOf<String>()
        HttpClient(
            MockEngine { request ->
                paths += request.url.encodedPath
                delay(100.milliseconds)
                respond("[${entry("Example")}]")
            },
        ).use { client ->
            val provider = ThunderstoreProvider(client)
            val results = listOf(
                async { provider.search(game, "Example", 0) },
                async { provider.search(game, "Author", 0) },
            ).awaitAll()
            assertTrue(results.all { it.page().total == 1 })
            assertEquals(1, paths.size)

            val other = provider.search(ProviderGameId("other-game"), "Example", 0).page()
            assertContains(other.mods.single().pageUrl, "/c/other-game/")
            assertEquals(listOf("/c/example-game/api/v1/package/", "/c/other-game/api/v1/package/"), paths)
        }
    }

    @Test
    fun malformedAndAmbiguousCatalogsFailExplicitly() = runTest {
        val cases = listOf(
            "not json",
            "{}",
            "[{\"name\":\"Incomplete\"}]",
            "[${entry("Example")},${entry("Example")}]",
            "[${entry("Example").replace("Author-Example", "Wrong-Example")}]",
            "[${entry("Example").replace("1.0.0", "")}]",
            "[${entry("../escape")}]",
        )
        for (body in cases) {
            HttpClient(MockEngine { respond(body) }).use { client ->
                assertEquals(failed(ProviderFailure.InvalidResponse), ThunderstoreProvider(client).search(game, "x", 0))
            }
        }
    }

    @Test
    fun httpFailuresAreAttributedToThunderstoreWithoutResponseDetails() = runTest {
        for ((status, failure) in mapOf(
            401 to ProviderFailure.AccessDenied,
            403 to ProviderFailure.AccessDenied,
            404 to ProviderFailure.GameNotFound,
            429 to ProviderFailure.RateLimited(12),
            503 to ProviderFailure.Unavailable,
            302 to ProviderFailure.Unavailable,
        )) {
            HttpClient(
                MockEngine {
                    respond(
                        "private response",
                        HttpStatusCode.fromValue(status),
                        headersOf(HttpHeaders.RetryAfter, "12"),
                    )
                },
            ) { followRedirects = false }.use { client ->
                assertEquals(failed(failure), ThunderstoreProvider(client).search(game, "x", 0))
            }
        }
    }

    @Test
    fun oversizedResponsesAndUnsafeCommunityIdsAreRejected() = runTest {
        HttpClient(
            MockEngine {
                respond("[]", headers = headersOf(HttpHeaders.ContentLength, "268435457"))
            },
        ).use { client ->
            assertEquals(failed(ProviderFailure.InvalidResponse), ThunderstoreProvider(client).search(game, "x", 0))
        }

        HttpClient(MockEngine { error("No request expected") }).use { client ->
            val provider = ThunderstoreProvider(client)
            assertEquals(failed(ProviderFailure.GameNotFound), provider.search(ProviderGameId("../other"), "x", 0))
            assertEquals(SearchOutcome.Failed(SearchFailure.InvalidOffset), provider.search(game, "x", -1))
        }
    }

    @Test
    fun networkFailureAndCancellationReleaseCacheLockForRetry() = runTest {
        var requests = 0
        HttpClient(
            MockEngine {
                requests++
                when (requests) {
                    1 -> throw IOException("network detail")
                    2 -> throw CancellationException()
                    else -> respond("[]")
                }
            },
        ).use { client ->
            val provider = ThunderstoreProvider(client)
            assertEquals(failed(ProviderFailure.Unavailable), provider.search(game, "x", 0))
            assertFailsWith<CancellationException> { provider.search(game, "x", 0) }
            assertEquals(0, provider.search(game, "x", 0).page().total)
        }
    }
}

private fun entry(name: String): String = """{
    "name":"$name","owner":"Author","full_name":"Author-$name","is_deprecated":false,
    "versions":[{"version_number":"1.0.0","description":"More space","is_active":true}]
}"""

private fun failed(failure: ProviderFailure) = SearchOutcome.Failed(SearchFailure.Provider("thunderstore", failure))
private fun SearchOutcome.page(): SearchPage = assertIs<SearchOutcome.Found>(this).page
