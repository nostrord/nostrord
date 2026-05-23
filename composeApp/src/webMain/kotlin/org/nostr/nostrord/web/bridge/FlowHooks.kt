package org.nostr.nostrord.web.bridge

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import react.useEffect
import react.useState

/**
 * Bridge between the shared Kotlin coroutine world (StateFlow / suspend) and React.
 *
 * The whole point of staying on kotlin-react (vs a separate TS app) is that the web
 * UI consumes `AppModule` / managers / their StateFlows directly — no @JsExport, no
 * serialization boundary. These hooks are the only adapter needed.
 *
 * kotlin-react's `useEffect` takes a `suspend CoroutineScope.() -> Unit` whose scope is
 * cancelled automatically on unmount or when a dependency changes, so collection is
 * torn down for us — no manual MainScope/cancel bookkeeping.
 */

/**
 * Collect a [StateFlow] into React state. Equivalent of Compose's `collectAsState()`.
 * Seeds from `flow.value` and re-renders on every emission.
 */
fun <T> useStateFlow(flow: StateFlow<T>): T {
    val (state, setState) = useState { flow.value }
    useEffect(flow) {
        flow.collect { setState(it) }
    }
    return state
}

/**
 * Collect a cold/hot [Flow] with no inherent current value, using [initial] until the
 * first emission.
 */
fun <T> useFlow(flow: Flow<T>, initial: T): T {
    val (state, setState) = useState { initial }
    useEffect(flow) {
        flow.collect { setState(it) }
    }
    return state
}
