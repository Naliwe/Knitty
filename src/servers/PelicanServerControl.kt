package knitty.servers

import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import knitty.core.model.*
import knitty.core.ports.ServerControl
import kotlinx.coroutines.delay
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.IOException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class PelicanServerControl(
    private val client: HttpClient,
    private val environment: (String) -> String? = System::getenv,
    private val pollInterval: Duration = 1.seconds,
    private val pollAttempts: Int = 120,
) : ServerControl {
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun inspect(target: ServerTarget): ProfileResult<ServerStatus> = guarded {
        verifyTarget(target)
        val power = powerState(target)
        val variables = get<PelicanCollection<PelicanVariable>>(target, "startup")
        val autoUpdate = variables.data.map { it.attributes }
            .singleOrNull { it.name == "AUTO_UPDATE" }
            ?: abort(ServerFailure.InvalidResponse)

        val value = autoUpdate.value ?: autoUpdate.default
        if (value !in listOf("0", "1")) abort(ServerFailure.InvalidResponse)

        ServerStatus(power, value == "1")
    }

    override suspend fun stop(target: ServerTarget): ProfileResult<Unit> =
        changePower(target, "stop", ServerPowerState.Offline)

    override suspend fun start(target: ServerTarget): ProfileResult<Unit> =
        changePower(target, "start", ServerPowerState.Running)

    private suspend fun changePower(
        target: ServerTarget,
        signal: String,
        expected: ServerPowerState,
    ): ProfileResult<Unit> = guarded {
        verifyTarget(target)
        request(target, "power", PelicanPowerSignal(signal))

        repeat(pollAttempts) {
            if (powerState(target) == expected) return@guarded
            delay(pollInterval)
        }

        abort(ServerFailure.PowerTimeout)
    }

    private suspend fun verifyTarget(target: ServerTarget) {
        val server = get<PelicanResource<PelicanServer>>(target, "").attributes
        val hosts = listOfNotNull(server.sftp.ip, server.sftp.alias)
        val sameHost = hosts.any { it.equals(target.sftp.host, ignoreCase = true) }
        val sameUser = target.sftp.username.substringAfterLast('.').equals(server.identifier, ignoreCase = true)
        val sameServer = server.uuid.equals(target.serverId, ignoreCase = true)
        val samePort = server.sftp.port == target.sftp.port

        if (!sameServer || !sameUser || !sameHost || !samePort) {
            abort(ServerFailure.InvalidTarget)
        }
        if (server.suspended || server.installing || server.transferring || server.maintenance) {
            abort(ServerFailure.ServerBusy)
        }
    }

    private suspend fun powerState(target: ServerTarget): ServerPowerState {
        val power = get<PelicanResource<PelicanPower>>(target, "resources").attributes

        return when (power.state) {
            "offline" -> ServerPowerState.Offline
            "running" -> ServerPowerState.Running
            "starting" -> ServerPowerState.Starting
            "stopping" -> ServerPowerState.Stopping
            else -> abort(ServerFailure.InvalidResponse)
        }
    }

    private suspend inline fun <reified T> get(target: ServerTarget, endpoint: String): T =
        json.decodeFromString(request(target, endpoint))

    private suspend fun request(
        target: ServerTarget,
        endpoint: String,
        signal: PelicanPowerSignal? = null,
    ): String {
        val token = environment("KNITTY_${target.name.uppercase()}_PELICAN_TOKEN")
            ?.takeIf { it.isNotBlank() }
            ?: abort(ServerFailure.MissingCredentials)
        val suffix = if (endpoint.isEmpty()) "" else "/$endpoint"
        val url = "${target.panelUrl.trimEnd('/')}/api/client/servers/${target.serverId}$suffix"

        val response = client.request(url) {
            method = if (signal == null) HttpMethod.Get else HttpMethod.Post
            bearerAuth(token)
            accept(ContentType.Application.Json)

            if (signal != null) {
                contentType(ContentType.Application.Json)
                setBody(json.encodeToString(signal))
            }
        }

        return when (response.status.value) {
            200 -> response.bodyAsText()
            204 -> if (signal != null) "" else abort(ServerFailure.InvalidResponse)
            401 -> abort(ServerFailure.AuthenticationFailed)
            403 -> abort(ServerFailure.PermissionDenied)
            409 -> abort(ServerFailure.ServerBusy)
            else -> abort(ServerFailure.InvalidResponse)
        }
    }

    private suspend fun <T> guarded(action: suspend () -> T): ProfileResult<T> = try {
        ProfileResult.Success(action())
    } catch (failure: PelicanFailure) {
        ProfileResult.Failed(ProfileFailure.Server(failure.failure))
    } catch (_: IOException) {
        ProfileResult.Failed(ProfileFailure.Server(ServerFailure.ConnectionFailed))
    } catch (_: SerializationException) {
        ProfileResult.Failed(ProfileFailure.Server(ServerFailure.InvalidResponse))
    } catch (_: IllegalArgumentException) {
        ProfileResult.Failed(ProfileFailure.Server(ServerFailure.InvalidResponse))
    }
}

private class PelicanFailure(val failure: ServerFailure) : Exception()

private fun abort(failure: ServerFailure): Nothing = throw PelicanFailure(failure)
