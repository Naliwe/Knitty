package knitty.settings

import kotlinx.coroutines.test.runTest
import java.nio.file.Path
import kotlin.io.path.*
import kotlin.test.*

class ProfileEnvironmentTest {
    @Test
    fun loadsLiteralCredentialsAndPreservesShellValuesIncludingEmptyOverrides() = runTest {
        directory { root ->
            root.resolve(".env").writeText(
                $$"""
                # Profile-local credentials
                KNITTY_OVH_PELICAN_TOKEN=file-token
                export KNITTY_OVH_SFTP_PASSWORD='spaces # dollars $ and = signs'
                KNITTY_OVH_SFTP_KEY_FILE="C:\keys\knitty" # a literal path
                KNITTY_MODIO_API_KEY=file-modio-key
                EMPTY=
                INLINE=plain-value # comment
                LITERAL=$HOME/`do-not-run`
                """.trimIndent(),
            )
            val environment =
                load(root, mapOf("KNITTY_OVH_PELICAN_TOKEN" to "shell-token", "KNITTY_MODIO_API_KEY" to ""))

            assertEquals("shell-token", environment["KNITTY_OVH_PELICAN_TOKEN"])
            assertEquals("spaces # dollars $ and = signs", environment["KNITTY_OVH_SFTP_PASSWORD"])
            assertEquals("C:\\keys\\knitty", environment["KNITTY_OVH_SFTP_KEY_FILE"])
            assertEquals("", environment["KNITTY_MODIO_API_KEY"])
            assertEquals("", environment["EMPTY"])
            assertEquals("plain-value", environment["INLINE"])
            assertEquals($$"$HOME/`do-not-run`", environment["LITERAL"])
            assertNull(environment["UNKNOWN"])
        }
    }

    @Test
    fun missingFileUsesProcessEnvironmentAndSelectedDirectoriesRemainIsolated() = runTest {
        directory { first ->
            directory { second ->
                assertEquals(
                    "shell",
                    load(first, mapOf("KNITTY_OVH_PELICAN_TOKEN" to "shell"))["KNITTY_OVH_PELICAN_TOKEN"],
                )
                first.resolve(".env").writeText("KNITTY_OVH_PELICAN_TOKEN=first")
                second.resolve(".env").writeText("KNITTY_OVH_PELICAN_TOKEN=second")

                assertEquals("first", load(first)["KNITTY_OVH_PELICAN_TOKEN"])
                assertEquals("second", load(second)["KNITTY_OVH_PELICAN_TOKEN"])
            }
        }
    }

    @Test
    fun rejectsMalformedAndDuplicateAssignmentsWithoutEchoingTheirContents() = runTest {
        directory { root ->
            for (invalid in listOf(
                "this-is-a-secret",
                "TOKEN='unterminated-secret",
                "TOKEN=\"secret\" trailing-secret",
                "INVALID-KEY=secret",
                "TOKEN=secret\u0000",
                "TOKEN=first-secret\nTOKEN=second-secret",
            )) {
                root.resolve(".env").writeText(invalid)
                val result = assertIs<EnvironmentOutcome.Failed>(ProfileEnvironment.load(root, process = { null }))
                val failure = assertIs<EnvironmentFailure.InvalidLine>(result.failure)
                assertEquals(if ('\n' in invalid) 2 else 1, failure.number)
                assertFalse(result.toString().contains("secret"))
            }
        }
    }

    @Test
    fun handlesBomCrLfAndReportsInvalidEncodingOversizeAndUnreadableFiles() = runTest {
        directory { root ->
            val file = root.resolve(".env")
            file.writeText("\uFEFFTOKEN=one\r\nNEXT='two'\r\n")
            assertEquals("two", load(root)["NEXT"])

            file.writeBytes(byteArrayOf(0xc3.toByte(), 0x28))
            assertEquals(EnvironmentOutcome.Failed(EnvironmentFailure.InvalidEncoding), ProfileEnvironment.load(root))
            file.writeText("x".repeat(65_537))
            assertEquals(EnvironmentOutcome.Failed(EnvironmentFailure.TooLarge), ProfileEnvironment.load(root))
            file.deleteExisting()
            file.createDirectory()
            assertEquals(EnvironmentOutcome.Failed(EnvironmentFailure.Unreadable), ProfileEnvironment.load(root))
            file.deleteExisting()
        }
    }
}

private suspend fun load(directory: Path, process: Map<String, String> = emptyMap()): ProfileEnvironment =
    assertIs<EnvironmentOutcome.Loaded>(ProfileEnvironment.load(directory, process::get)).environment

private suspend fun directory(action: suspend (Path) -> Unit) {
    val root = createTempDirectory("knitty-env-test-")
    try {
        action(root)
    } finally {
        root.resolve(".env").deleteIfExists()
        root.deleteExisting()
    }
}
