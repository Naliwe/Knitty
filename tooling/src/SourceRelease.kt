package knitty.tooling

import java.nio.file.Path
import kotlin.io.path.name

internal class SourceRelease(private val root: Path) {
    fun assemble(version: String, output: Path = root.resolve("dist")): Path {
        releaseVersion(version)

        return stagingDirectory(root) { staging ->
            val pending = staging.resolve("knitty-$version-source.tar.gz")
            // Archive the committed revision, never local credentials, caches or build products.
            runProcess(
                listOf(
                    "git", "-c", "tar.umask=0022", "archive", "--format=tar.gz", "--prefix=knitty/",
                    "--output=$pending", "HEAD",
                ),
                root,
            )

            publishArtifact(pending, output.resolve(pending.name))
        }
    }
}
