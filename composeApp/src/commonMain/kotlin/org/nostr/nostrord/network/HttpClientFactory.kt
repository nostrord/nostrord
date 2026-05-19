package org.nostr.nostrord.network

import io.ktor.client.*

expect fun createHttpClient(): HttpClient

/** Minimal HTTP client for NIP-11 — no ContentNegotiation, no WebSockets. */
expect fun createNip11HttpClient(): HttpClient

/**
 * Whether the platform's WebSocket engine performs ws-level ping/pong on its
 * own. Returns `true` on JVM/Android (Ktor `pingInterval`) and `false` on
 * browser targets where the WebSocket API hides ping frames from JS code.
 *
 * When `false`, [NostrGroupClient] sends a periodic best-effort REQ/CLOSE
 * probe to keep relay-side and intermediary timers happy. The probe never
 * decides liveness — that is delegated to the engine's frame loop.
 */
expect fun hasEngineWebSocketPing(): Boolean
