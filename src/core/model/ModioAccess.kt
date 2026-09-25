package knitty.core.model

class ModioAccess private constructor(val apiPath: String, val apiKey: String) {
    override fun toString(): String = "ModioAccess(redacted)"

    companion object {
        fun parse(apiPath: String, apiKey: String): ModioAccessOutcome {
            val path = apiPath.trim().trimEnd('/')
            val key = apiKey.trim()
            if (!Regex("https://[ug]-[1-9][0-9]*\\.(modapi\\.io|test\\.mod\\.io)/v1").matches(path)) {
                return ModioAccessOutcome.Invalid(ModioAccessFailure.InvalidEndpoint)
            }
            if (key.isEmpty()) return ModioAccessOutcome.Invalid(ModioAccessFailure.EmptyKey)
            if (key.length > 512) return ModioAccessOutcome.Invalid(ModioAccessFailure.KeyTooLong)
            if (key.any { it.code !in 33..126 || it == '\'' || it == '"' }) {
                return ModioAccessOutcome.Invalid(ModioAccessFailure.InvalidKeyCharacters)
            }

            return ModioAccessOutcome.Valid(ModioAccess(path, key))
        }
    }
}

sealed interface ModioAccessOutcome {
    data class Valid(val access: ModioAccess) : ModioAccessOutcome
    data class Invalid(val failure: ModioAccessFailure) : ModioAccessOutcome
}

sealed interface ModioAccessFailure {
    data object InvalidEndpoint : ModioAccessFailure
    data object EmptyKey : ModioAccessFailure
    data object KeyTooLong : ModioAccessFailure
    data object InvalidKeyCharacters : ModioAccessFailure
}

sealed interface ModioSetupOutcome {
    data object Saved : ModioSetupOutcome
    data object FilesystemFailure : ModioSetupOutcome
}
