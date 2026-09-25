package knitty.tooling

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.readText
import kotlin.io.path.relativeTo
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ArchitectureTest {
    private val source = projectRoot().resolve("src")
    private val imports = Regex("^import\\s+([\\w.*]+)", RegexOption.MULTILINE)
    private val applicationApi = setOf(
        "SearchMods", "SearchRequest", "PlanInstall", "PlanInstallRequest",
        "ManageProfile", "ApplyChangePlan", "SetupSteamLibrary", "ConfigureModio",
    )

    @Test
    fun coreDependenciesPointInward() {
        for (path in sources("core")) {
            val layer = path.relativeTo(source).getName(1).toString()
            val allowed = listOf("kotlin.", "knitty.core.model.") + when (layer) {
                "ports" -> listOf("knitty.core.ports.")
                "application" -> listOf("knitty.core.ports.", "knitty.core.application.", "kotlinx.coroutines.")
                else -> emptyList()
            }

            for (dependency in dependencies(path)) {
                assertTrue(allowed.any(dependency::startsWith), "$path: $dependency points outside core")
                assertFalse(dependency.startsWith("kotlin.io."), "$path: filesystem work belongs in an adapter")
            }
        }
    }

    @Test
    fun frontendsUseApplicationContractsAndSharedPresentation() {
        for (layer in listOf("cli", "tui", "terminal")) {
            val allowed = listOf("knitty.$layer.", "knitty.core.model.", "knitty.terminal.")
            for (path in sources(layer)) {
                for (dependency in dependencies(path).filter { it.startsWith("knitty.") }) {
                    if (dependency.startsWith("knitty.core.application.") && layer != "terminal") {
                        assertTrue(dependency.substringAfterLast('.') in applicationApi, "$path: $dependency")
                    } else {
                        assertTrue(allowed.any(dependency::startsWith), "$path: $dependency bypasses application ports")
                    }
                }
            }
        }
    }

    @Test
    fun drivenAdaptersRemainIndependentAndDiStaysInCompositionRoot() {
        for (layer in listOf("providers", "games", "profiles", "settings", "filesystem", "servers")) {
            val allowed = listOf("knitty.$layer.", "knitty.core.model.", "knitty.core.ports.", "knitty.filesystem.")
            for (path in sources(layer)) {
                for (dependency in dependencies(path).filter { it.startsWith("knitty.") }) {
                    assertTrue(allowed.any(dependency::startsWith), "$path: $dependency crosses adapter boundaries")
                }
            }
        }

        for (path in sources("")) {
            if (path.parent == source) continue
            assertFalse(dependencies(path).any { it.startsWith("org.koin.") }, "$path: DI belongs in composition root")
        }
    }

    private fun sources(layer: String): List<Path> = Files.walk(source.resolve(layer)).use { paths ->
        paths.filter { it.extension == "kt" }.toList()
    }

    private fun dependencies(path: Path): List<String> =
        imports.findAll(path.readText()).map { it.groupValues[1] }.toList()
}
