package knitty.terminal

import knitty.core.model.ProviderFailure

internal fun providerFailureMessage(failure: ProviderFailure, provider: String = "modio"): String {
    val name = if (provider == "modio") "mod.io" else plainText(provider)
    return when (failure) {
        ProviderFailure.MissingCredentials -> "Run knitty auth to save your own mod.io API key, or set KNITTY_MODIO_API_KEY."
        ProviderFailure.InvalidEndpoint -> "Set KNITTY_MODIO_API_PATH to the HTTPS API path from your mod.io API access page (for example https://u-12345.modapi.io/v1)."
        ProviderFailure.AccessDenied -> if (provider == "modio") {
            "mod.io denied access. Check your API key and its game access."
        } else {
            "$name denied access. Retry later or check whether the service is accessible on your network."
        }

        is ProviderFailure.RateLimited -> failure.retryAfterSeconds?.let { "$name rate limit reached. Retry in $it seconds." }
            ?: "$name rate limit reached. Try again later."

        ProviderFailure.PackageNotFound -> "Package or published file not found on $name."
        ProviderFailure.GameNotFound -> "The game catalog is unavailable on $name."
        ProviderFailure.Unavailable -> "Cannot reach $name. Check your connection and try again."
        ProviderFailure.InvalidResponse -> "$name returned an unsupported response. Try again later."
    }
}
