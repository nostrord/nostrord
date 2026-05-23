package org.nostr.nostrord.web.bridge

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch

/**
 * App-lifetime scope for fire-and-forget suspend calls triggered from React event
 * handlers (e.g. `onClick = { launchApp { repo.sendMessage(...) } }`).
 *
 * These actions must outlive the component that triggered them — a message send
 * should complete even if the user navigates away — so they intentionally do NOT use
 * a per-component scope. Work that should be cancelled on unmount belongs in a
 * `useEffect` cleanup instead (see [useStateFlow]).
 */
private val webAppScope: CoroutineScope = MainScope()

fun launchApp(block: suspend CoroutineScope.() -> Unit): Job = webAppScope.launch(block = block)
