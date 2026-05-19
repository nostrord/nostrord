package org.nostr.nostrord.network

import io.ktor.client.*
import io.ktor.client.engine.darwin.*
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.websocket.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.Json

actual fun createHttpClient(): HttpClient = HttpClient(Darwin) {
    install(HttpTimeout) {
        connectTimeoutMillis = 10_000
        requestTimeoutMillis = 30_000
        socketTimeoutMillis = 15_000
    }
    install(WebSockets)
    install(ContentNegotiation) {
        json(
            Json {
                ignoreUnknownKeys = true
                isLenient = true
            },
        )
    }
}

// Darwin engine relies on NSURLSession's network-layer keepalive plus the
// system's transport-level checks, so application-level probes are unneeded.
actual fun hasEngineWebSocketPing(): Boolean = true

actual fun createNip11HttpClient(): HttpClient = HttpClient(Darwin) {
    install(HttpTimeout) {
        connectTimeoutMillis = 5_000
        requestTimeoutMillis = 8_000
        socketTimeoutMillis = 8_000
    }
}
