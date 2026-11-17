package dk.nota.flutterreadium

import org.junit.Assert.assertEquals
import org.junit.Test
import org.readium.r2.navigator.DecorableNavigator

/**
 * The reader fragment replaces its navigator every time the app resumes. Taps on a
 * decoration used to stop reaching Dart after that: the listener had been registered on
 * the first navigator only, and the plugin never registered it again.
 */
internal class DecorationListenersTest {
    /** Stands for one navigator: records what gets registered on it. */
    private class Navigator {
        val registered = mutableListOf<Pair<String, DecorableNavigator.Listener>>()

        fun addDecorationListener(
            group: String,
            listener: DecorableNavigator.Listener,
        ) {
            registered.add(group to listener)
        }
    }

    private val listener =
        object : DecorableNavigator.Listener {
            override fun onDecorationActivated(event: DecorableNavigator.OnActivatedEvent) = true
        }

    @Test
    fun theNavigatorCreatedOnResumeGetsTheListenerToo() {
        val listeners = DecorationListeners()
        val beforePause = Navigator()
        val afterResume = Navigator()

        listeners.add("highlights", listener, beforePause::addDecorationListener)
        listeners.attachTo(afterResume::addDecorationListener)

        assertEquals(listOf("highlights" to listener), beforePause.registered)
        assertEquals(listOf("highlights" to listener), afterResume.registered)
    }

    @Test
    fun aListenerAddedBeforeAnyNavigatorExistsIsNotLost() {
        val listeners = DecorationListeners()
        val navigator = Navigator()

        listeners.add("highlights", listener, null)
        listeners.attachTo(navigator::addDecorationListener)

        assertEquals(listOf("highlights" to listener), navigator.registered)
    }
}
