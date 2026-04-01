package org.nostr.nostrord.network

import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.websocket.*
import io.ktor.serialization.kotlinx.json.*
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.json.Json

actual fun createHttpClient(): HttpClient = HttpClient(OkHttp) {
    engine {
        config {
            connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            readTimeout(0, java.util.concurrent.TimeUnit.SECONDS) // 0 required for WebSocket idle
        }
    }
    // Ktor-level timeouts apply to HTTP requests (get/post/put) only, NOT WebSocket
    // frames. This prevents uploads and metadata fetches from hanging indefinitely
    // while keeping WebSocket connections alive.
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

actual fun createNip11HttpClient(): HttpClient = HttpClient(OkHttp) {
    install(HttpTimeout) {
        connectTimeoutMillis = 10_000
        requestTimeoutMillis = 15_000
        socketTimeoutMillis = 15_000
    }
    followRedirects = true
}
