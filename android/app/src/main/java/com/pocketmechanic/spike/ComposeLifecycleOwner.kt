package com.pocketmechanic.spike

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner

/**
 * The lifecycle, saved-state and view-model owners a `ComposeView` needs.
 *
 * Neither host is a `ComponentActivity`: the overlay is a `WindowManager` window
 * with no Activity at all, and the dashboard is a plain `Activity` that hosts a
 * WebView plus one `ComposeView`. Both therefore have to supply the owners the
 * ViewTree integration expects, and both use this one implementation so the
 * composition is torn down the same way in each.
 *
 * Call order is create -> start -> (composition alive) -> destroy; the
 * `DisposeOnViewTreeLifecycleDestroyed` strategy is what turns [destroy] into an
 * actual disposal of the composition.
 */
internal class OverlayLifecycleOwner : LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner {
    private val registry = LifecycleRegistry(this)
    private val savedState = SavedStateRegistryController.create(this)
    private val store = ViewModelStore()

    override val lifecycle: Lifecycle get() = registry
    override val savedStateRegistry: SavedStateRegistry get() = savedState.savedStateRegistry
    override val viewModelStore: ViewModelStore get() = store

    fun create() {
        savedState.performRestore(null)
        registry.currentState = Lifecycle.State.CREATED
    }

    fun start() {
        registry.currentState = Lifecycle.State.RESUMED
    }

    fun destroy() {
        registry.currentState = Lifecycle.State.DESTROYED
        store.clear()
    }
}
