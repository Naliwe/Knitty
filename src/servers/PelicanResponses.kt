package knitty.servers

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal data class PelicanResource<T>(val attributes: T)

@Serializable
internal data class PelicanCollection<T>(val data: List<PelicanResource<T>>)

@Serializable
internal data class PelicanServer(
    val uuid: String,
    val identifier: String,
    @SerialName("sftp_details") val sftp: PelicanSftp,
    @SerialName("is_suspended") val suspended: Boolean = false,
    @SerialName("is_installing") val installing: Boolean = false,
    @SerialName("is_transferring") val transferring: Boolean = false,
    @SerialName("is_node_under_maintenance") val maintenance: Boolean = false,
)

@Serializable
internal data class PelicanSftp(val ip: String? = null, val alias: String? = null, val port: Int)

@Serializable
internal data class PelicanPower(@SerialName("current_state") val state: String)

@Serializable
internal data class PelicanVariable(
    @SerialName("env_variable") val name: String,
    @SerialName("server_value") val value: String? = null,
    @SerialName("default_value") val default: String? = null,
)

@Serializable
internal data class PelicanPowerSignal(val signal: String)
