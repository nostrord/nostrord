package org.nostr.nostrord.web.bridge

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.awaitCancellation
import react.useEffect
import react.useRef
import react.useState

/**
 * Create a commonMain [ViewModel] and keep one stable instance for the component's
 * lifetime, the web analogue of Compose's `viewModel { }`. The same ViewModel class
 * backs both UIs, so screen logic (state flows + actions) is written and tested once in
 * commonMain instead of being reimplemented in React.
 *
 * The instance is held in a [ViewModelStore] so its `viewModelScope` is cancelled on
 * unmount: the `useEffect` scope is torn down when the component unmounts, and the
 * `finally` clears the store. This mirrors how `viewModel { }` ties the scope to the
 * Compose lifecycle on native, so an action launched on `viewModelScope` (a join, a
 * relay switch) is cancelled when the user leaves the screen on both web and native.
 */
fun <T : ViewModel> useViewModel(factory: () -> T): T {
    val (store) = useState { ViewModelStore() }
    val (vm) = useState { factory().also { store.put("vm", it) } }
    useEffect(vm) {
        try {
            awaitCancellation()
        } finally {
            store.clear()
        }
    }
    return vm
}

/**
 * Keyed variant of [useViewModel] — the web analogue of Compose's `viewModel(key = …) { }`.
 * When [key] changes, the previous ViewModel is disposed (its scope cancelled) and a fresh
 * one is created from [factory]. Use this when one mounted component is reused across
 * different subjects (e.g. ChatScreen staying mounted while the open group changes), so the
 * VM's per-key state (its groupId) stays correct.
 *
 * Each key generation gets its own [ViewModelStore]; the effect captures that store per
 * render, so switching keys runs the previous render's cleanup (disposing the old VM) while
 * the new VM is already in place, and unmount disposes the current one.
 */
fun <T : ViewModel> useViewModel(key: Any?, factory: () -> T): T {
    val keyRef = useRef<Any>(null)
    val storeRef = useRef<ViewModelStore>(null)
    val vmRef = useRef<ViewModel>(null)

    if (vmRef.current == null || keyRef.current != key) {
        val store = ViewModelStore()
        vmRef.current = factory().also { store.put("vm", it) }
        storeRef.current = store
        keyRef.current = key
    }

    val store = storeRef.current
    useEffect(key) {
        try {
            awaitCancellation()
        } finally {
            store?.clear()
        }
    }

    @Suppress("UNCHECKED_CAST")
    return vmRef.current as T
}
