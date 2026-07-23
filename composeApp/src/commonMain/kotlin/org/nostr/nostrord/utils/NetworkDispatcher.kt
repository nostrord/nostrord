package org.nostr.nostrord.utils

import kotlinx.coroutines.CoroutineDispatcher

/**
 * Dispatcher for relay/websocket client scopes. Never use Dispatchers.Default for
 * these: Ktor's ws handshake calls generateNonce(), whose JVM implementation parks
 * the calling thread in runBlocking until a GlobalScope producer on
 * Dispatchers.Default refills the nonce channel. With client scopes on Default,
 * N concurrent connects (relay pool + reconnect chains) can park every Default
 * worker at once, the producer never gets a thread, and the whole dispatcher
 * deadlocks permanently: logout freezes on "Signing out...", login hangs on
 * loading, only killing the app recovers. IO grows elastically so parked
 * handshakes never starve it; JS is single-threaded with no runBlocking, so
 * Default is safe there.
 */
expect val networkClientDispatcher: CoroutineDispatcher
