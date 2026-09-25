package knitty.providers.modio

import io.ktor.client.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.*
import knitty.core.model.*
import knitty.core.ports.*
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.IOException
import java.net.URI
import java.net.URISyntaxException

class ModioProvider(
    private val client: HttpClient,
    private val apiKey: String?,
    private val baseUrl: String?,
) : ModProvider, GetInstallCandidate, DownloadArtifact {
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun search(game: ProviderGameId, query: String, offset: Int): SearchOutcome =
        request({ SearchOutcome.Failed(SearchFailure.Provider("modio", it)) }) {
            val gameId = gameId(game)
            val parameters = mapOf(
                "_q" to query,
                "_offset" to offset.toString(),
                "_limit" to "20",
                "_sort" to "name",
            )

            val response = json.decodeFromString<ModsDto>(get("games/$gameId/mods", parameters))
            val invalidPage = response.offset != offset || response.total < 0 ||
                response.data.any { it.id <= 0 || it.gameId != gameId } ||
                (response.data.isNotEmpty() && response.total.toLong() < offset.toLong() + response.data.size)
            if (invalidPage) throw ModioRequestFailure(ProviderFailure.InvalidResponse)

            SearchOutcome.Found(SearchPage(response.data.map { it.toMod() }, response.offset, response.total))
        }

    override suspend fun get(game: ProviderGameId, reference: PackageReference): InstallCandidateOutcome =
        request({ InstallCandidateOutcome.Failed(PlanInstallFailure.Provider(it)) }) {
            if (reference.provider != "modio") {
                return@request InstallCandidateOutcome.Failed(PlanInstallFailure.UnsupportedProvider(reference.provider))
            }
            if (!packageReferencePattern.matches(reference.value)) {
                return@request InstallCandidateOutcome.Failed(PlanInstallFailure.InvalidPackageReference)
            }

            val gameId = gameId(game)
            val mod = findMod(gameId, reference.value)
            if (mod.id <= 0 || mod.gameId != gameId) {
                throw ModioRequestFailure(ProviderFailure.InvalidResponse)
            }
            if (mod.status != 1 || mod.visible != 1) {
                return@request InstallCandidateOutcome.Failed(PlanInstallFailure.ArtifactUnavailable)
            }

            val dependencies = dependencies(gameId, mod.id, ProviderFailure.PackageNotFound)

            val fileId = mod.modfile?.id
                ?: return@request InstallCandidateOutcome.Failed(PlanInstallFailure.NoPublishedFile)
            if (fileId <= 0) throw ModioRequestFailure(ProviderFailure.InvalidResponse)

            val file = json.decodeFromString<InstallFileDto>(
                get(
                    "games/$gameId/mods/${mod.id}/files/$fileId",
                    missing = ProviderFailure.PackageNotFound,
                ),
            )
            val invalidFile = file.id != fileId || file.modId != mod.id || file.sizeBytes <= 0 ||
                file.filename.isBlank() || (file.hash?.md5?.let { !md5Pattern.matches(it) } == true)
            if (invalidFile) throw ModioRequestFailure(ProviderFailure.InvalidResponse)
            if (file.download?.url?.startsWith("https://") != true || file.virusPositive != 0) {
                return@request InstallCandidateOutcome.Failed(PlanInstallFailure.ArtifactUnavailable)
            }

            val artifact = PackageArtifact(
                PackageVersion(ArtifactId(file.id.toString()), file.version?.takeIf { it.isNotBlank() }),
                file.filename,
                file.sizeBytes,
                file.hash?.md5?.let { Checksum(ChecksumAlgorithm.MD5, it.lowercase()) },
            )

            InstallCandidateOutcome.Found(
                InstallCandidate(
                    id = PackageId("modio", mod.id.toString()),
                    name = mod.name,
                    artifact = artifact,
                    dependencies = dependencies,
                ),
            )
        }

    override suspend fun download(
        game: ProviderGameId,
        change: Change.Install,
        sink: ArtifactSink,
    ): DownloadOutcome = request({ DownloadOutcome.Failed(ApplyFailure.Provider(it)) }) {
        val modId = change.id.value.toLongOrNull()?.takeIf { it > 0 }
        val fileId = change.artifact.version.artifactId.value.toLongOrNull()?.takeIf { it > 0 }
        if (change.id.provider != "modio" || modId == null || fileId == null) {
            return@request DownloadOutcome.Failed(ApplyFailure.InvalidPlan)
        }

        val gameId = gameId(game)
        val mod = findMod(gameId, change.id.value)
        if (mod.id != modId || mod.gameId != gameId || mod.status != 1 || mod.visible != 1) {
            return@request DownloadOutcome.Failed(ApplyFailure.ArtifactChanged)
        }
        if (dependencies(gameId, modId).toSet() != change.dependencies.toSet()) {
            return@request DownloadOutcome.Failed(ApplyFailure.DependenciesChanged)
        }

        // URLs expire; refresh the pinned file, never the package's current release.
        val file = json.decodeFromString<InstallFileDto>(
            get("games/$gameId/mods/$modId/files/$fileId", missing = ProviderFailure.PackageNotFound),
        )
        val expected = change.artifact
        val unchanged = file.id == fileId && file.modId == modId && file.virusPositive == 0 &&
            file.sizeBytes == expected.sizeBytes && file.filename == expected.filename &&
            file.hash?.md5.equals(expected.checksum?.value, ignoreCase = true)
        if (!unchanged) return@request DownloadOutcome.Failed(ApplyFailure.ArtifactChanged)

        val url = file.download?.url
            ?: return@request DownloadOutcome.Failed(ApplyFailure.DownloadFailed)
        streamArtifact(url, sink)
    }

    private suspend fun dependencies(
        gameId: Long,
        modId: Long,
        missing: ProviderFailure = ProviderFailure.GameNotFound,
    ): List<PackageId> {
        val ids = linkedSetOf<PackageId>()
        var offset = 0
        var expectedTotal: Int? = null

        do {
            val response = get(
                "games/$gameId/mods/$modId/dependencies",
                mapOf("_limit" to "100", "_offset" to offset.toString(), "recursive" to "false"),
                missing,
            )
            val page = json.decodeFromString<DependenciesDto>(response)
            if (page.offset != offset || page.total !in 0..500 ||
                (expectedTotal != null && page.total != expectedTotal) ||
                offset + page.data.size > page.total || (page.data.isEmpty() && offset < page.total)
            ) {
                throw ModioRequestFailure(ProviderFailure.InvalidResponse)
            }

            expectedTotal = page.total
            for ((id) in page.data) {
                if (id <= 0 || !ids.add(PackageId("modio", id.toString()))) {
                    throw ModioRequestFailure(ProviderFailure.InvalidResponse)
                }
            }
            offset += page.data.size
        } while (offset < expectedTotal)

        return ids.sortedBy { it.value.toLong() }
    }

    private suspend fun streamArtifact(initialUrl: String, sink: ArtifactSink): DownloadOutcome {
        var url = initialUrl
        return try {
            repeat(4) {
                val uri = URI(url)
                if (uri.scheme != "https" || uri.host == null || uri.userInfo != null) {
                    return DownloadOutcome.Failed(ApplyFailure.DownloadFailed)
                }
                var redirect: String? = null
                val outcome = client.prepareGet(url) {
                    // This request deliberately carries no API credentials.
                    timeout { requestTimeoutMillis = 300_000 }
                }.execute { response ->
                    if (response.status.value in listOf(301, 302, 303, 307, 308)) {
                        redirect = response.headers[HttpHeaders.Location]?.let { uri.resolve(it).toString() }
                        return@execute DownloadOutcome.Failed(ApplyFailure.DownloadFailed)
                    }
                    if (response.status.value != 200) {
                        return@execute DownloadOutcome.Failed(ApplyFailure.DownloadFailed)
                    }

                    val channel = response.bodyAsChannel()
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val count = channel.readAvailable(buffer, 0, buffer.size)
                        if (count < 0) break
                        if (count > 0) sink.write(buffer, count)
                    }

                    DownloadOutcome.Downloaded
                }
                url = redirect ?: return outcome
            }
            DownloadOutcome.Failed(ApplyFailure.DownloadFailed)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IOException) {
            DownloadOutcome.Failed(ApplyFailure.DownloadFailed)
        } catch (_: HttpRequestTimeoutException) {
            DownloadOutcome.Failed(ApplyFailure.DownloadFailed)
        } catch (_: URISyntaxException) {
            DownloadOutcome.Failed(ApplyFailure.DownloadFailed)
        }
    }

    private suspend fun gameId(game: ProviderGameId): Long {
        val games = json.decodeFromString<GamesDto>(get("games", mapOf("name_id" to game.value)))
        val id = games.data.singleOrNull { it.slug == game.value }?.id
            ?: throw ModioRequestFailure(ProviderFailure.GameNotFound)
        if (id <= 0) throw ModioRequestFailure(ProviderFailure.InvalidResponse)

        return id
    }

    private suspend fun findMod(gameId: Long, reference: String): ModDto {
        if (reference.all { it.isDigit() }) {
            val id = reference.toLongOrNull()?.takeIf { it > 0 }
                ?: throw ModioRequestFailure(ProviderFailure.PackageNotFound)
            val mod = json.decodeFromString<ModDto>(
                get(
                    "games/$gameId/mods/$id",
                    missing = ProviderFailure.PackageNotFound,
                ),
            )
            if (mod.id != id) throw ModioRequestFailure(ProviderFailure.InvalidResponse)
            return mod
        }

        val response = json.decodeFromString<ModsDto>(
            get(
                "games/$gameId/mods",
                mapOf("name_id" to reference, "_limit" to "1"),
                ProviderFailure.PackageNotFound,
            ),
        )
        return response.data.singleOrNull { it.slug == reference }
            ?: throw ModioRequestFailure(ProviderFailure.PackageNotFound)
    }

    private suspend fun <T> request(failed: (ProviderFailure) -> T, block: suspend () -> T): T {
        if (apiKey.isNullOrBlank()) return failed(ProviderFailure.MissingCredentials)
        if (baseUrl == null || !apiPathPattern.matches(baseUrl)) {
            return failed(ProviderFailure.InvalidEndpoint)
        }

        return try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: ModioRequestFailure) {
            failed(failure.failure)
        } catch (_: SerializationException) {
            failed(ProviderFailure.InvalidResponse)
        } catch (_: IOException) {
            failed(ProviderFailure.Unavailable)
        } catch (_: HttpRequestTimeoutException) {
            failed(ProviderFailure.Unavailable)
        }
    }

    private suspend fun get(
        path: String,
        parameters: Map<String, String> = emptyMap(),
        missing: ProviderFailure = ProviderFailure.GameNotFound,
    ): String {
        val response = client.get("${baseUrl!!.trimEnd('/')}/$path") {
            accept(ContentType.Application.Json)
            parameter("api_key", apiKey)
            parameters.forEach { (name, value) -> parameter(name, value) }
        }
        val failure = when (response.status.value) {
            200 -> null
            401, 403 -> ProviderFailure.AccessDenied
            404 -> missing
            429 -> ProviderFailure.RateLimited(
                response.headers[HttpHeaders.RetryAfter]?.toLongOrNull()?.takeIf { it >= 0 },
            )

            else -> ProviderFailure.Unavailable
        }
        if (failure != null) throw ModioRequestFailure(failure)
        return response.bodyAsText()
    }

}

private val apiPathPattern = Regex("https://[ug]-[1-9][0-9]*\\.(modapi\\.io|test\\.mod\\.io)/v1/?")

private class ModioRequestFailure(val failure: ProviderFailure) : Exception()

private val packageReferencePattern = Regex("[a-z0-9][a-z0-9-]*")
private val md5Pattern = Regex("[a-fA-F0-9]{32}")
