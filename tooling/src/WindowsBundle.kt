package knitty.tooling

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse.BodyHandlers
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.time.Duration
import kotlin.io.path.*

internal class WindowsBundle(
    private val root: Path,
    private val archives: ReleaseArchives = ReleaseArchives(),
) {
    private val json = Json { prettyPrint = true }

    fun assemble(profile: Path? = null): Path {
        val templates = root.resolve("packaging/windows")
        val runtime = json.decodeFromString<WindowsRuntime>(templates.resolve("runtime.json").readText())
        require(runtime.platform == "windows-x64") { "Expected a Windows x64 runtime." }

        val archive = cachedRuntime(runtime)

        return stagingDirectory(root) { staging ->
            val bundle = staging.resolve("Knitty").createDirectory()
            archives.extractRuntime(archive, bundle.resolve("runtime"), runtime.sha256)
            copyReleaseFile(executableJar(root), bundle.resolve("knitty.jar"))
            copyReleaseFile(root.resolve("LICENSE"), bundle.resolve("LICENSE"))
            copyReleaseFile(root.resolve("THIRD-PARTY-NOTICES.md"), bundle.resolve("THIRD-PARTY-NOTICES.md"))
            copyReleaseFile(root.resolve("scripts/test_windows_bundle.ps1"), bundle.resolve("test_windows_bundle.ps1"))

            for (name in listOf(
                "Start Knitty.cmd", "Setup Knitty.cmd", "Test Knitty.cmd", "knitty.cmd",
                "launch.ps1", "credentials.ps1", "README.txt", "TESTING.txt",
            )) {
                val contents = templates.resolve(name).readText()
                require(contents.all { it.code < 128 }) { "Windows PowerShell templates must remain ASCII." }
                bundle.resolve(name).writeText(contents.replace("\r\n", "\n").replace("\n", "\r\n"))
            }

            val profileDirectory = bundle.resolve("profile")
            if (profile != null) {
                copyProfile(profile, profileDirectory)
            } else {
                profileDirectory.createDirectory().resolve("PUT PROFILE HERE.txt").writeText(
                    "Copy the shared knitty.yaml and knitty.lock into this folder, then run Start Knitty.cmd.\r\n",
                )
            }

            val info = BundleInfo(runtime, sha256(bundle.resolve("knitty.jar")))
            bundle.resolve("build-info.json").writeText(json.encodeToString(info) + "\n")

            val pending = staging.resolve("knitty-windows-x64.zip")
            archives.zip(bundle, pending)
            publishArtifact(pending, root.resolve("dist").resolve(pending.name))
        }
    }

    private fun cachedRuntime(runtime: WindowsRuntime): Path {
        require(Regex("[a-f0-9]{64}").matches(runtime.sha256)) { "Invalid runtime checksum." }
        val cache = root.resolve("build/windows-runtime").createDirectories()
        val archive = cache.resolve("${runtime.sha256}.zip")
        if (archive.exists()) return archive

        val partial = cache.resolve("${runtime.sha256}.download")
        println("Downloading ${runtime.vendor} ${runtime.version} (Windows x64)...")

        try {
            downloadRuntime(URI.create(runtime.url), partial)
            require(sha256(partial) == runtime.sha256) { "Downloaded runtime checksum does not match runtime.json." }
            partial.moveTo(archive)
        } finally {
            partial.deleteIfExists()
        }

        return archive
    }

    private fun downloadRuntime(uri: URI, destination: Path) {
        require(uri.scheme == "https") { "Runtime downloads require HTTPS." }

        // NORMAL follows redirects but refuses HTTPS-to-HTTP downgrades.
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build()
            .use { client ->
                val request = HttpRequest.newBuilder(uri).timeout(Duration.ofMinutes(10)).build()
                val response = client.send(request, BodyHandlers.ofFile(destination))
                check(response.statusCode() == 200) { "Runtime download failed: HTTP ${response.statusCode()}" }
            }
    }
}

internal fun copyProfile(profile: Path, destination: Path) {
    val names = listOf("knitty.yaml", "knitty.lock")
    for (name in names) {
        require(profile.resolve(name).isRegularFile(NOFOLLOW_LINKS)) { "Profile needs a regular $name file." }
    }

    destination.createDirectories()
    for (name in names) {
        copyReleaseFile(profile.resolve(name), destination.resolve(name))
    }
}

@Serializable
internal data class WindowsRuntime(
    val vendor: String,
    val version: String,
    val platform: String,
    val url: String,
    val sha256: String,
)

@Serializable
private data class BundleInfo(val runtime: WindowsRuntime, val jarSha256: String)
