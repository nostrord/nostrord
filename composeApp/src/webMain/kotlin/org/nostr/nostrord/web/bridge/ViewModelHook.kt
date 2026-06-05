package org.nostr.nostrord.web.bridge

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.awaitCancellation
import react.useEffect
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
