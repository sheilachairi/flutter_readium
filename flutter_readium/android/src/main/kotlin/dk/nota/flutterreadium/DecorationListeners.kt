package dk.nota.flutterreadium

import org.readium.r2.navigator.DecorableNavigator

/** Registers a decoration listener for a group on a navigator: `DecorableNavigator::addDecorationListener`. */
internal typealias RegisterDecorationListener = (group: String, listener: DecorableNavigator.Listener) -> Unit

/**
 * Remembers the decoration listeners the plugin asked for, so every navigator the reader
 * creates gets them.
 *
 * The reader fragment drops its Readium navigator in `onPause` and builds a new one in
 * `onResume`. A listener registered on the old navigator dies with it, so taps on a
 * decoration stopped reaching Dart after the app came back from the background: the
 * decorations were still painted, but nobody was listening.
 */
internal class DecorationListeners {
    private val listeners = mutableMapOf<String, MutableList<DecorableNavigator.Listener>>()

    /**
     * Remembers [listener] for [group] and registers it on the current navigator, when there
     * is one: [register] is that navigator's `addDecorationListener`.
     */
    fun add(
        group: String,
        listener: DecorableNavigator.Listener,
        register: RegisterDecorationListener?,
    ) {
        listeners.getOrPut(group) { mutableListOf() }.add(listener)
        register?.invoke(group, listener)
    }

    /** Registers every remembered listener on a navigator that has just been created. */
    fun attachTo(register: RegisterDecorationListener) {
        listeners.forEach { (group, groupListeners) ->
            groupListeners.forEach { register(group, it) }
        }
    }
}
