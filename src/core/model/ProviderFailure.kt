package knitty.core.model

sealed interface ProviderFailure {
    data object MissingCredentials : ProviderFailure
    data object InvalidEndpoint : ProviderFailure
    data object AccessDenied : ProviderFailure
    data class RateLimited(val retryAfterSeconds: Long?) : ProviderFailure
    data object GameNotFound : ProviderFailure
    data object PackageNotFound : ProviderFailure
    data object Unavailable : ProviderFailure
    data object InvalidResponse : ProviderFailure
}
