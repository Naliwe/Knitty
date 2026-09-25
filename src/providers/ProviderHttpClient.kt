package knitty.providers

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*

fun providerHttpClient() = HttpClient(CIO) {
    expectSuccess = false
    followRedirects = false
    install(HttpTimeout) {
        requestTimeoutMillis = 15_000
        connectTimeoutMillis = 5_000
        socketTimeoutMillis = 15_000
    }
}
