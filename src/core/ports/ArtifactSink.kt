package knitty.core.ports

fun interface ArtifactSink {
    suspend fun write(bytes: ByteArray, count: Int)
}
