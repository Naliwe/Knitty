package knitty

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import knitty.core.application.SearchRequest
import knitty.core.model.GameId
import knitty.core.model.ProfileFailure
import knitty.core.model.ProfileResult
import knitty.core.model.SearchOutcome
import knitty.settings.EnvironmentOutcome
import knitty.settings.ProfileEnvironment
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectory
import kotlin.io.path.writeText
import kotlin.test.*

class ApplicationServicesTest {
    @Test
    fun profileScopesKeepCredentialsAndStoresSeparate() = runTest {
        val root = Files.createTempDirectory("knitty-wiring-")
        val first = root.resolve("first").createDirectory()
        val second = root.resolve("second").createDirectory()
        first.resolve("knitty.yaml").writeText("schemaVersion: 1\nid: first\ngame: core-keeper\nmods: []\n")
        val keys = mutableListOf<String?>()
        val engine = MockEngine { request ->
            keys += request.url.parameters["api_key"]
            if (request.url.encodedPath == "/v1/games") {
                respond("""{"data":[{"id":42,"name_id":"corekeeper"}]}""")
            } else {
                respond("""{"data":[],"result_offset":0,"result_total":0,"result_count":0,"result_limit":20}""")
            }
        }

        try {
            HttpClient(engine).use { client ->
                ApplicationServices(client, root.resolve("settings.json")).use { services ->
                    suspend fun profile(directory: Path, key: String): ProfileServices {
                        val values = mapOf(
                            "KNITTY_MODIO_API_KEY" to key,
                            "KNITTY_MODIO_API_PATH" to "https://u-123.modapi.io/v1",
                        )
                        val loaded = ProfileEnvironment.load(directory, values::get)
                        val environment = assertIs<EnvironmentOutcome.Loaded>(loaded)

                        return services.profile(directory.toString(), environment.environment)
                    }

                    val a = profile(first, "first-key")
                    val b = profile(second, "second-key")
                    val request = SearchRequest(GameId("core-keeper"), "storage")

                    assertIs<SearchOutcome.Found>(a.search.search(request))
                    assertIs<SearchOutcome.Found>(b.search.search(request))
                    assertEquals(listOf<String?>("first-key", "first-key", "second-key", "second-key"), keys)
                    assertIs<ProfileResult.Success<*>>(a.profiles.lock(request.game))
                    assertEquals(ProfileResult.Failed(ProfileFailure.MissingProfile), b.profiles.lock(request.game))
                }
            }
        } finally {
            Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        }
    }
}
