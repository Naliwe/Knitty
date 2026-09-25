package knitty.tooling

import java.io.PrintWriter
import java.io.StringWriter
import java.util.spi.ToolProvider
import kotlin.io.path.*
import kotlin.test.*

class JavaRuntimeTest {
    @Test
    fun linkedRuntimeIsValidatedWithoutFullJdkPlatformMetadata() = stagingDirectory(projectRoot()) { root ->
        val runtime = root.resolve("linked runtime")
        val output = StringWriter()
        val writer = PrintWriter(output)
        val jlink = ToolProvider.findFirst("jlink").orElseThrow()

        val exitCode = jlink.run(
            writer,
            writer,
            "--add-modules", "java.base",
            "--strip-native-commands",
            "--output", runtime.toString(),
        )
        assertEquals(0, exitCode, output.toString())
        requireJava21Runtime(runtime)

        runtime.resolve("release").writeText("JAVA_VERSION=\"21.0.12\"\nMODULES=\"java.base\"\n")
        assertEquals("\"21.0.12\"", requireJava21Runtime(runtime).getProperty("JAVA_VERSION"))
    }

    @Test
    fun packagingToolMustBelongToAJava21WindowsX64Jdk() = stagingDirectory(projectRoot()) { root ->
        val executable = root.resolve("jdk with spaces/bin/jpackage.exe")
        executable.parent.createDirectories()
        executable.writeText("fixture")
        val release = executable.parent.parent.resolve("release")

        for (architecture in listOf("amd64", "x86_64")) {
            release.writeText("JAVA_VERSION=\"21.0.12\"\nOS_ARCH=\"$architecture\"\nOS_NAME=\"Windows\"\n")
            assertEquals(executable.toRealPath(), windowsPackagingTool(executable))
        }

        for (metadata in listOf(
            "JAVA_VERSION=\"25.0.1\"\nOS_ARCH=\"amd64\"\nOS_NAME=\"Windows\"\n",
            "JAVA_VERSION=\"21.0.12\"\nOS_ARCH=\"aarch64\"\nOS_NAME=\"Windows\"\n",
            "JAVA_VERSION=\"21.0.12\"\nOS_ARCH=\"amd64\"\nOS_NAME=\"Linux\"\n",
            "JAVA_VERSION=\"21.0.12\"\n",
            "OS_ARCH=\"amd64\"\nOS_NAME=\"Windows\"\n",
        )) {
            release.writeText(metadata)
            assertFailsWith<IllegalArgumentException> { windowsPackagingTool(executable) }
        }
    }

    @Test
    fun missingOrWrongRuntimeVersionHasAnActionableError() = stagingDirectory(projectRoot()) { root ->
        for (metadata in listOf("", "JAVA_VERSION=\"25.0.1\"\n")) {
            root.resolve("release").writeText(metadata)

            val failure = assertFailsWith<IllegalArgumentException> { requireJava21Runtime(root) }
            assertContains(failure.message.orEmpty(), "requires Java 21")
            assertContains(failure.message.orEmpty(), "JAVA_VERSION=")
        }
    }
}
