package knitty.settings

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import kotlin.io.path.inputStream

class ProfileEnvironment private constructor(
    private val values: Map<String, String>,
    private val process: (String) -> String?,
    private val defaults: ProfileEnvironment?,
) {
    operator fun get(name: String): String? = process(name) ?: values[name] ?: defaults?.get(name)

    companion object {
        suspend fun load(
            directory: Path,
            process: (String) -> String? = System::getenv,
            defaults: ProfileEnvironment? = null,
            fileName: String = ".env",
        ): EnvironmentOutcome = withContext(Dispatchers.IO) {
            val bytes = try {
                directory.resolve(fileName).inputStream().use { it.readNBytes(65_537) }
            } catch (_: NoSuchFileException) {
                return@withContext EnvironmentOutcome.Loaded(ProfileEnvironment(emptyMap(), process, defaults))
            } catch (_: IOException) {
                return@withContext EnvironmentOutcome.Failed(EnvironmentFailure.Unreadable)
            }
            if (bytes.size > 65_536) return@withContext EnvironmentOutcome.Failed(EnvironmentFailure.TooLarge)

            val text = try {
                bytes.decodeToString(throwOnInvalidSequence = true).removePrefix("\uFEFF")
            } catch (_: CharacterCodingException) {
                return@withContext EnvironmentOutcome.Failed(EnvironmentFailure.InvalidEncoding)
            }

            val values = mutableMapOf<String, String>()
            for ((index, source) in text.lineSequence().withIndex()) {
                val line = source.trim()
                if (line.isEmpty() || line.startsWith('#')) continue

                val assignment = line.removePrefix("export ").removePrefix("export\t").trimStart()
                val separator = assignment.indexOf('=')
                val name = assignment.substring(0, separator.coerceAtLeast(0)).trim()
                val validName = Regex("[A-Za-z_][A-Za-z0-9_]*").matches(name)
                if (separator < 0 || !validName || name in values || '\u0000' in line) {
                    return@withContext EnvironmentOutcome.Failed(EnvironmentFailure.InvalidLine(index + 1))
                }

                val value = parseValue(assignment.substring(separator + 1).trim())
                    ?: return@withContext EnvironmentOutcome.Failed(EnvironmentFailure.InvalidLine(index + 1))
                values[name] = value
            }

            EnvironmentOutcome.Loaded(ProfileEnvironment(values.toMap(), process, defaults))
        }

        private fun parseValue(value: String): String? {
            if (value.startsWith('"') || value.startsWith('\'')) {
                val end = value.indexOf(value.first(), startIndex = 1)
                if (end < 0) return null

                val suffix = value.substring(end + 1).trimStart()
                if (suffix.isNotEmpty() && !suffix.startsWith('#')) return null

                return value.substring(1, end)
            }

            val comment = Regex("\\s+#").find(value)?.range?.first ?: value.length
            return value.substring(0, comment).trimEnd()
        }
    }
}

sealed interface EnvironmentOutcome {
    data class Loaded(val environment: ProfileEnvironment) : EnvironmentOutcome
    data class Failed(val failure: EnvironmentFailure) : EnvironmentOutcome
}

sealed interface EnvironmentFailure {
    data object Unreadable : EnvironmentFailure
    data object TooLarge : EnvironmentFailure
    data object InvalidEncoding : EnvironmentFailure
    data class InvalidLine(val number: Int) : EnvironmentFailure
}
