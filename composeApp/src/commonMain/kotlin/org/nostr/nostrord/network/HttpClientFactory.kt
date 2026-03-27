package org.nostr.nostrord.network

import io.ktor.client.*

expect fun createHttpClient(): HttpClient

/** Minimal HTTP client for NIP-11 — no ContentNegotiation, no WebSockets. */
expect fun createNip11HttpClient(): HttpClient
