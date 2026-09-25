package knitty.providers.modio

import knitty.core.model.ModSearchResult
import knitty.core.model.PackageId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal data class GamesDto(val data: List<GameDto>)

@Serializable
internal data class GameDto(val id: Long, @SerialName("name_id") val slug: String)

@Serializable
internal data class ModsDto(
    val data: List<ModDto>,
    @SerialName("result_offset") val offset: Int,
    @SerialName("result_total") val total: Int,
)

@Serializable
internal data class ModDto(
    val id: Long,
    @SerialName("game_id") val gameId: Long,
    @SerialName("name_id") val slug: String,
    val name: String,
    val status: Int? = null,
    val visible: Int? = null,
    val summary: String,
    @SerialName("submitted_by") val author: AuthorDto,
    @SerialName("profile_url") val pageUrl: String,
    val modfile: ModfileDto? = null,
) {
    fun toMod() = ModSearchResult(
        id = PackageId("modio", id.toString()),
        slug = slug,
        name = name,
        summary = summary,
        author = author.username,
        version = modfile?.version?.takeIf { it.isNotBlank() },
        pageUrl = pageUrl,
    )
}

@Serializable
internal data class AuthorDto(val username: String)

@Serializable
internal data class ModfileDto(val id: Long? = null, val version: String? = null)

@Serializable
internal data class DependenciesDto(
    val data: List<DependencyDto>,
    @SerialName("result_offset") val offset: Int,
    @SerialName("result_total") val total: Int,
)

@Serializable
internal data class DependencyDto(@SerialName("mod_id") val id: Long)

@Serializable
internal data class InstallFileDto(
    val id: Long,
    @SerialName("mod_id") val modId: Long,
    val filename: String,
    @SerialName("filesize") val sizeBytes: Long,
    val version: String? = null,
    @SerialName("filehash") val hash: FileHashDto? = null,
    val download: DownloadDto? = null,
    @SerialName("virus_positive") val virusPositive: Int,
)

@Serializable
internal data class FileHashDto(val md5: String? = null)

@Serializable
internal data class DownloadDto(@SerialName("binary_url") val url: String)
