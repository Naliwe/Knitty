package knitty.servers

import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import knitty.core.model.*
import knitty.sampleServer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class PelicanServerControlTest {
    @Test
    fun typedResponsesAcceptFutureFieldsAndUseDefaultStartupValues() = runTest {
        val engine = MockEngine { request ->
            when (request.url.encodedPath.substringAfterLast('/')) {
                sampleServer.serverId -> respond(serverDetails)
                "resources" -> respond("""{"attributes":{"current_state":"offline","future":true}}""")
                "startup" -> respond(
                    """{"data":[{"attributes":{"env_variable":"AUTO_UPDATE","server_value":null,"default_value":"0"}}]}""",
                )

                else -> error("Unexpected request")
            }
        }

        HttpClient(engine).use { client ->
            val result = PelicanServerControl(client, { "token" }).inspect(sampleServer)

            assertEquals(ProfileResult.Success(ServerStatus(ServerPowerState.Offline, false)), result)
        }
    }

    @Test
    fun malformedTypedFieldsAndEmptyGetResponsesFailExplicitly() = runTest {
        for ((body, status) in listOf(
            serverDetails.replace("2022", "true") to HttpStatusCode.OK,
            serverDetails.replace("\"uuid\":", "\"future\":") to HttpStatusCode.OK,
            "" to HttpStatusCode.NoContent,
        )) {
            HttpClient(MockEngine { respond(body, status) }).use { client ->
                val result = PelicanServerControl(client, { "token" }).inspect(sampleServer)

                assertEquals(ProfileResult.Failed(ProfileFailure.Server(ServerFailure.InvalidResponse)), result)
            }
        }
    }

    @Test
    fun mapsPowerAndAutoUpdateAndWaitsForGracefulStop() = runTest {
        var state = "running"
        val engine = MockEngine { request ->
            assertEquals("Bearer private-token", request.headers[HttpHeaders.Authorization])
            assertContains(request.url.encodedPath, sampleServer.serverId)
            when (request.url.encodedPath.substringAfterLast('/')) {
                sampleServer.serverId -> respond(serverDetails)
                "resources" -> respond("""{"attributes":{"current_state":"$state"}}""")
                "startup" -> respond("""{"data":[{"attributes":{"env_variable":"AUTO_UPDATE","server_value":"1"}}]}""")
                "power" -> {
                    assertEquals(HttpMethod.Post, request.method)
                    assertEquals("""{"signal":"stop"}""", request.body.toByteArray().decodeToString())
                    state = "offline"
                    respond("", HttpStatusCode.NoContent)
                }

                else -> error("Unexpected request")
            }
        }
        HttpClient(engine).use { client ->
            val control = PelicanServerControl(client, { "private-token" })
            assertEquals(
                ProfileResult.Success(ServerStatus(ServerPowerState.Running, true)),
                control.inspect(sampleServer),
            )
            assertEquals(ProfileResult.Success(Unit), control.stop(sampleServer))
            assertEquals("offline", state)
        }
    }

    @Test
    fun authenticationPermissionsMalformedResponsesAndTimeoutsAreExplicit() = runTest {
        for ((status, failure) in listOf(
            HttpStatusCode.Unauthorized to ServerFailure.AuthenticationFailed,
            HttpStatusCode.Forbidden to ServerFailure.PermissionDenied,
            HttpStatusCode.OK to ServerFailure.InvalidResponse,
        )) {
            HttpClient(MockEngine { respond("{}", status) }).use { client ->
                assertEquals(
                    ProfileResult.Failed(ProfileFailure.Server(failure)),
                    PelicanServerControl(client, { "token" }).inspect(sampleServer),
                )
            }
        }

        val engine = MockEngine {
            when {
                it.url.encodedPath.endsWith(sampleServer.serverId) -> respond(serverDetails)
                it.method == HttpMethod.Post -> respond("", HttpStatusCode.NoContent)
                else -> respond("""{"attributes":{"current_state":"stopping"}}""")
            }
        }
        HttpClient(engine).use { client ->
            val result = PelicanServerControl(client, { "token" }, pollAttempts = 2).stop(sampleServer)
            assertEquals(ProfileResult.Failed(ProfileFailure.Server(ServerFailure.PowerTimeout)), result)
            assertEquals(1, engine.requestHistory.count { it.method == HttpMethod.Post })
        }
    }

    @Test
    fun mismatchedSftpAndPelicanServersAreRejectedBeforeAnyPowerOrFileOperation() = runTest {
        val engine = MockEngine { respond(serverDetails.replace("sftp.example.com", "other.example.com")) }
        HttpClient(engine).use { client ->
            val control = PelicanServerControl(client, { "token" })
            assertEquals(
                ProfileResult.Failed(ProfileFailure.Server(ServerFailure.InvalidTarget)),
                control.inspect(sampleServer),
            )
            assertEquals(
                ProfileResult.Failed(ProfileFailure.Server(ServerFailure.InvalidTarget)),
                control.stop(sampleServer),
            )
            assertTrue(engine.requestHistory.all { it.method == HttpMethod.Get })
        }
    }

    @Test
    fun missingTokenDoesNotConnectAndCancellationPropagates() = runTest {
        HttpClient(MockEngine { error("No credentials") }).use { client ->
            assertEquals(
                ProfileResult.Failed(ProfileFailure.Server(ServerFailure.MissingCredentials)),
                PelicanServerControl(client, { null }).inspect(sampleServer),
            )
        }
        HttpClient(MockEngine { throw CancellationException("cancelled") }).use { client ->
            assertFailsWith<CancellationException> {
                PelicanServerControl(client, { "token" }).inspect(sampleServer)
            }
        }
    }
}

private val serverDetails = """{
    "attributes": {
        "uuid": "${sampleServer.serverId}",
        "identifier": "12345678",
        "sftp_details": {"ip": "sftp.example.com", "alias": null, "port": 2022}
    }
}"""
