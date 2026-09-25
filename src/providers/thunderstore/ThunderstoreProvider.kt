package knitty.providers.thunderstore

import io.ktor.client.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.jvm.javaio.*
import knitty.core.model.*
import knitty.core.ports.ModProvider
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.DecodeSequenceMode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeToSequence
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeMark
import kotlin.time.TimeSource

class ThunderstoreProvider(
    private val client: HttpClient,
    private val timeSource: TimeSource = TimeSource.Monotonic,
) : ModProvider {
    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()
    private var cached: Catalog? = null

    override suspend fun search(game: ProviderGameId, query: String, offset: Int): SearchOutcome {
        if (!communityPattern.matches(game.value)) return failed(ProviderFailure.GameNotFound)
        if (offset < 0) return SearchOutcome.Failed(SearchFailure.InvalidOffset)

        return try {
            val catalog = mutex.withLock {
                val previous = cached
                if (previous != null && previous.game == game && previous.loadedAt.elapsedNow() < 5.minutes) {
                    previous
                } else {
                    val mods = fetchCatalog(game)
                    Catalog(game, mods, timeSource.markNow()).also { cached = it }
                }
            }

            withContext(Dispatchers.Default) {
                val matches = catalog.mods.filter { mod ->
                    currentCoroutineContext().ensureActive()
                    mod.name.contains(query, ignoreCase = true) ||
                        mod.id.value.contains(query, ignoreCase = true) ||
                        mod.summary.contains(query, ignoreCase = true)
                }

                SearchOutcome.Found(SearchPage(matches.drop(offset).take(20), offset, matches.size))
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: CatalogFailure) {
            failed(failure.failure)
        } catch (_: SerializationException) {
            failed(ProviderFailure.InvalidResponse)
        } catch (_: IOException) {
            failed(ProviderFailure.Unavailable)
        } catch (_: HttpRequestTimeoutException) {
            failed(ProviderFailure.Unavailable)
        }
    }

    @OptIn(ExperimentalSerializationApi::class)
    private suspend fun fetchCatalog(game: ProviderGameId): List<ModSearchResult> =
        client.prepareGet("https://thunderstore.io/c/${game.value}/api/v1/package/") {
            accept(ContentType.Application.Json)
            timeout { requestTimeoutMillis = 60_000 }
        }.execute { response ->
            val failure = when (response.status.value) {
                200 -> null
                401, 403 -> ProviderFailure.AccessDenied
                404 -> ProviderFailure.GameNotFound
                429 -> ProviderFailure.RateLimited(
                    response.headers[HttpHeaders.RetryAfter]?.toLongOrNull()?.takeIf { it >= 0 },
                )

                else -> ProviderFailure.Unavailable
            }
            if (failure != null) throw CatalogFailure(failure)
            if ((response.headers[HttpHeaders.ContentLength]?.toLongOrNull() ?: 0) > maxCatalogBytes) {
                throw CatalogFailure(ProviderFailure.InvalidResponse)
            }

            withContext(Dispatchers.IO) {
                val context = currentCoroutineContext()
                CatalogInputStream(response.bodyAsChannel().toInputStream(context.job), context).use { stream ->
                    val entries = json.decodeToSequence<PackageDto>(stream, DecodeSequenceMode.ARRAY_WRAPPED)
                    mapCatalog(entries, game, context)
                }
            }
        }

    private fun mapCatalog(
        entries: Sequence<PackageDto>,
        game: ProviderGameId,
        context: CoroutineContext,
    ): List<ModSearchResult> {
        val identities = mutableSetOf<String>()
        val mods = entries.mapNotNull { entry ->
            context.ensureActive()
            if (!namePattern.matches(entry.name) || !ownerPattern.matches(entry.owner) ||
                entry.fullName != "${entry.owner}-${entry.name}" || !identities.add(entry.fullName) ||
                identities.size > 100_000
            ) {
                throw CatalogFailure(ProviderFailure.InvalidResponse)
            }
            if (entry.deprecated) return@mapNotNull null

            // The v1 API orders active versions by descending semantic version.
            val version = entry.versions.firstOrNull { it.active }
                ?: return@mapNotNull null
            if (version.number.isBlank()) throw CatalogFailure(ProviderFailure.InvalidResponse)

            ModSearchResult(
                PackageId("thunderstore", entry.fullName),
                entry.name,
                entry.name,
                version.description,
                entry.owner,
                version.number,
                "https://thunderstore.io/c/${game.value}/p/${entry.owner}/${entry.name}/",
            )
        }.toList()

        return mods.sortedWith { first, second ->
            val byName = first.name.compareTo(second.name, ignoreCase = true)
            if (byName != 0) byName else first.id.value.compareTo(second.id.value)
        }
    }
}

private class CatalogInputStream(input: InputStream, private val context: CoroutineContext) : FilterInputStream(input) {
    private var consumed = 0L

    override fun read(): Int {
        context.ensureActive()
        val value = super.read()
        if (value >= 0) count(1)

        return value
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        context.ensureActive()
        val read = super.read(buffer, offset, length)
        if (read > 0) count(read)

        return read
    }

    private fun count(bytes: Int) {
        consumed += bytes
        if (consumed > maxCatalogBytes) throw CatalogFailure(ProviderFailure.InvalidResponse)
    }
}

private data class Catalog(val game: ProviderGameId, val mods: List<ModSearchResult>, val loadedAt: TimeMark)
private class CatalogFailure(val failure: ProviderFailure) : Exception()

private fun failed(failure: ProviderFailure): SearchOutcome =
    SearchOutcome.Failed(SearchFailure.Provider("thunderstore", failure))

private const val maxCatalogBytes = 256L * 1024 * 1024
private val communityPattern = Regex("[a-z0-9]+(?:-[a-z0-9]+)*")
private val namePattern = Regex("[A-Za-z0-9_]+")
private val ownerPattern = Regex("[A-Za-z0-9_]+(?:-[A-Za-z0-9_]+)*")

@Serializable
private data class PackageDto(
    val name: String,
    @SerialName("full_name") val fullName: String,
    val owner: String,
    @SerialName("is_deprecated") val deprecated: Boolean,
    val versions: List<VersionDto>,
)

@Serializable
private data class VersionDto(
    @SerialName("version_number") val number: String,
    val description: String,
    @SerialName("is_active") val active: Boolean,
)
