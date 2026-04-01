package org.nostr.nostrord.network

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.websocket.*
import io.ktor.serialization.kotlinx.json.*
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.json.Json

actual fun createHttpClient(): HttpClient = HttpClient(CIO) {
    engine {
        requestTimeout = 0 // 0 required for WebSocket idle
    }
    install(HttpTimeout) {
        connectTimeoutMillis = 10_000
        requestTimeoutMillis = 30_000
        socketTimeoutMillis = 15_000
    }
    install(WebSockets) {
        pingInterval = 20.seconds
    }
    install(ContentNegotiation) {
        json(Json {
            ignoreUnknownKeys = true
            isLenient = true
        })
    }
}

actual fun createNip11HttpClient(): HttpClient = HttpClient(CIO) {
    // Each NIP-11 client instance is used for exactly one request and then closed, so we
    // don't need persistent connection pools — just honest timeouts. Relays on Caddy/HTTP-2
    // can be slow; 15 s gives them ample time without blocking the UI indefinitely.
    install(HttpTimeout) {
        connectTimeoutMillis = 10_000
        requestTimeoutMillis = 15_000
        socketTimeoutMillis = 15_000
    }
    followRedirects = true
}
