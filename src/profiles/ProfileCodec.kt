package knitty.profiles

import knitty.core.model.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.snakeyaml.engine.v2.api.Dump
import org.snakeyaml.engine.v2.api.DumpSettings
import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings
import org.snakeyaml.engine.v2.common.FlowStyle
import java.net.URI

internal class ProfileFileFailure(val failure: ProfileFailure) : Exception()

internal fun profileFailure(failure: ProfileFailure): Nothing = throw ProfileFileFailure(failure)

class ProfileCodec {
    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
    }

    private val yamlSettings = LoadSettings.builder()
        .setAllowDuplicateKeys(false)
        .setMaxAliasesForCollections(0)
        .setCodePointLimit(1_048_576)
        .build()

    fun decodeProfile(text: String): Profile = try {
        val map = Load(yamlSettings).loadFromString(text) as? Map<*, *>
            ?: invalidProfile()
        val version = map["schemaVersion"]
        val keys = setOf("schemaVersion", "id", "game", "mods")
        if (version !in listOf(1, 2) || map.keys != keys + if (version == 2) setOf("servers") else emptySet()) {
            invalidProfile()
        }

        val id = map["id"] as? String
            ?: invalidProfile()
        val game = map["game"] as? String
            ?: invalidProfile()
        if (!validProfileId(id) || !Regex("[a-z0-9-]+").matches(game)) invalidProfile()

        val entries = map["mods"] as? List<*>
            ?: invalidProfile()
        val mods = entries.map { entry ->
            val text = entry as? String
                ?: invalidProfile()
            parseId(text)
        }
        if (mods.size > 500 || mods.distinct().size != mods.size) invalidProfile()

        val servers = if (version == 2) decodeServers(map["servers"]) else emptyList()

        Profile(id, GameId(game), mods, servers)
    } catch (failure: ProfileFileFailure) {
        throw failure
    } catch (_: RuntimeException) {
        invalidProfile()
    }

    fun encodeProfile(profile: Profile): String {
        val document = linkedMapOf(
            "schemaVersion" to if (profile.servers.isEmpty()) 1 else 2,
            "id" to profile.id,
            "game" to profile.game.value,
            "mods" to profile.packages.map { "${it.provider}:${it.value}" },
        )

        if (profile.servers.isNotEmpty()) {
            document["servers"] = profile.servers.associate { (name, panelUrl, serverId, sftp) ->
                name to mapOf(
                    "panelUrl" to panelUrl,
                    "serverId" to serverId,
                    "sftp" to mapOf(
                        "host" to sftp.host,
                        "port" to sftp.port,
                        "username" to sftp.username,
                        "hostKey" to sftp.hostKey,
                    ),
                )
            }
        }

        val settings = DumpSettings.builder()
            .setDefaultFlowStyle(FlowStyle.BLOCK)
            .setIndent(2)
            .setIndicatorIndent(2)
            .setIndentWithIndicator(true)
            .build()

        return Dump(settings).dumpToString(document)
    }

    private fun decodeServers(value: Any?): List<ServerTarget> {
        val servers = value as? Map<*, *>
            ?: invalidProfile()
        if (servers.size > 20) invalidProfile()

        return servers.map { (name, server) -> decodeServer(name, server) }
    }

    private fun decodeServer(key: Any?, value: Any?): ServerTarget {
        val name = key as? String
            ?: invalidProfile()
        if (!Regex("[a-z][a-z0-9_]{0,31}").matches(name)) invalidProfile()

        val server = value as? Map<*, *>
            ?: invalidProfile()
        if (server.keys != setOf("panelUrl", "serverId", "sftp")) invalidProfile()

        val panel = server["panelUrl"] as? String
            ?: invalidProfile()
        val uri = URI.create(panel)
        val validAuthority = uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null
        val validLocation = uri.query == null && uri.fragment == null && !uri.path.orEmpty().contains("..")
        val validPort = uri.port == -1 || uri.port in 1..65535
        if (!validAuthority || !validLocation || !validPort) invalidProfile()

        val id = server["serverId"] as? String
            ?: invalidProfile()
        if (!Regex("[a-fA-F0-9]{8}(-[a-fA-F0-9]{4}){3}-[a-fA-F0-9]{12}").matches(id)) invalidProfile()

        return ServerTarget(name, panel.trimEnd('/'), id, decodeSftp(server["sftp"]))
    }

    private fun decodeSftp(value: Any?): SftpEndpoint {
        val sftp = value as? Map<*, *>
            ?: invalidProfile()
        if (sftp.keys != setOf("host", "port", "username", "hostKey")) invalidProfile()

        val host = sftp["host"] as? String
            ?: invalidProfile()
        val port = sftp["port"] as? Int
            ?: invalidProfile()
        val username = sftp["username"] as? String
            ?: invalidProfile()
        val hostKey = sftp["hostKey"] as? String
            ?: invalidProfile()

        val validAddress = Regex("[a-zA-Z0-9.:-]{1,253}").matches(host) && port in 1..65535
        val validUser = Regex("[a-zA-Z0-9_.@-]{1,128}").matches(username)
        val validHostKey = Regex("SHA256:[A-Za-z0-9+/]{43}=?").matches(hostKey)
        if (!validAddress || !validUser || !validHostKey) {
            invalidProfile()
        }

        return SftpEndpoint(host, port, username, hostKey)
    }

    fun decodeLock(text: String): ProfileLock = try {
        val dto = json.decodeFromString<LockDto>(text)
        if (dto.schemaVersion !in 1..2 || !validProfileId(dto.profileId) || dto.packages.size > 500) invalidLock()

        val packages = dto.packages.map(::decodePackage)
        if (packages.map { it.id }.distinct().size != packages.size) invalidLock()
        if (dto.schemaVersion == 1 && packages.any { it.dependencies.isNotEmpty() }) invalidLock()
        if (orderPackages(packages.map { it.id }, packages) !is PackageGraphOutcome.Ordered) invalidLock()

        ProfileLock(dto.profileId, GameId(dto.game), packages)
    } catch (failure: ProfileFileFailure) {
        throw failure
    } catch (_: RuntimeException) {
        invalidLock()
    }

    fun encodeLock(lock: ProfileLock): String = json.encodeToString(
        LockDto(
            schemaVersion = 2,
            profileId = lock.profileId,
            game = lock.game.value,
            packages = lock.packages.map { item ->
                LockedDto(
                    id = "${item.id.provider}:${item.id.value}",
                    name = item.name,
                    fileId = item.artifact.version.artifactId.value,
                    version = item.artifact.version.label,
                    filename = item.artifact.filename,
                    sizeBytes = item.artifact.sizeBytes,
                    md5 = item.artifact.checksum?.value,
                    dependencies = item.dependencies.map { "${it.provider}:${it.value}" },
                )
            },
        ),
    ) + "\n"

    private fun decodePackage(entry: LockedDto): LockedPackage {
        val id = try {
            parseId(entry.id)
        } catch (_: ProfileFileFailure) {
            invalidLock()
        }

        val validFileId = Regex("[1-9][0-9]*").matches(entry.fileId)
        val validSize = entry.sizeBytes in 1..536_870_912L
        if (!validFileId || !validSize || entry.filename.isBlank() || entry.name.isBlank()) {
            invalidLock()
        }
        if (entry.md5 != null && !Regex("[a-f0-9]{32}").matches(entry.md5)) invalidLock()

        val artifact = PackageArtifact(
            version = PackageVersion(ArtifactId(entry.fileId), entry.version),
            filename = entry.filename,
            sizeBytes = entry.sizeBytes,
            checksum = entry.md5?.let { Checksum(ChecksumAlgorithm.MD5, it) },
        )
        val dependencies = try {
            entry.dependencies.map(::parseId)
        } catch (_: ProfileFileFailure) {
            invalidLock()
        }

        return LockedPackage(id, entry.name, artifact, dependencies)
    }

    private fun parseId(text: String): PackageId {
        if (!Regex("modio:[1-9][0-9]*").matches(text)) invalidProfile()
        return PackageId("modio", text.substringAfter(':'))
    }

    private fun invalidProfile(): Nothing = profileFailure(ProfileFailure.InvalidProfile)
    private fun invalidLock(): Nothing = profileFailure(ProfileFailure.InvalidLock)
}

internal fun validProfileId(id: String): Boolean = Regex("[a-zA-Z0-9_-]{1,80}").matches(id)

@Serializable
private data class LockDto(
    val schemaVersion: Int,
    val profileId: String,
    val game: String,
    val packages: List<LockedDto>,
)

@Serializable
private data class LockedDto(
    val id: String,
    val name: String,
    val fileId: String,
    val version: String?,
    val filename: String,
    val sizeBytes: Long,
    val md5: String?,
    val dependencies: List<String> = emptyList(),
)
