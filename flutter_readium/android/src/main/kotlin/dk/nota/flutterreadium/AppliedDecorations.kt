package dk.nota.flutterreadium

import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.readium.r2.navigator.Decoration

/**
 * The kotlin-toolkit [AppliedDecorations] was written against, after reading how it sends
 * decorations. A test fails when the plugin moves to another one, so someone reads it again.
 */
internal const val DECORATIONS_READ_AGAINST_TOOLKIT = "3.4.0"

/** What [AppliedDecorations] needs from the Readium navigator. */
internal interface DecorationTarget {
    /** `DecorableNavigator.applyDecorations`. */
    suspend fun applyDecorations(
        decorations: List<Decoration>,
        group: String,
    )

    /** Whether one of its resources is still loading, so a script sent to it now may be dropped. */
    fun isLoadingResources(): Boolean
}

/**
 * The decorations each group should show, sent to the navigator only when no resource can drop them.
 *
 * kotlin-toolkit records the decorations as applied, works out the changes in the background, and
 * sends each resource its script only if the resource is loaded. A resource paints what is recorded
 * when its HTML finishes (`onResourceLoaded`), but counts as loaded only a moment later, once laid
 * out. A script for it in between is dropped, and nothing sends it again: the same decorations once
 * more are no change in the toolkit's eyes. Reopening a book ran into it every few times. The app
 * applies them as soon as the first resource loads, often a neighbour of the one being opened, and
 * the page it reopened at showed none of its decorations.
 *
 * So nothing is sent while a resource is loading: it waits for a page to load or change. A resource
 * that starts loading after a send paints what is recorded, so it misses nothing. Sending anyway and
 * repainting later would not do: only clearing a group makes the toolkit send it again, and the
 * clear and the decorations reach a page as two scripts, tens of milliseconds apart, so the
 * decorations already painted would blink.
 */
internal class AppliedDecorations {
    private val groups = mutableMapOf<String, List<Decoration>>()

    /**
     * The groups still to send, in order, and whether each has to be cleared first. A group emptied
     * and filled again before it goes is still cleared: `EpubNavigator` empties one to move its
     * decorations to another resource, and the toolkit would leave them behind otherwise.
     */
    private val unsent = linkedMapOf<String, Boolean>()

    /** One send at a time: the toolkit works out the changes in the background, and two could land out of order. */
    private val sending = Mutex()

    /** Remembers [decorations] for [group], and sends them to [target] unless something is loading. */
    suspend fun show(
        group: String,
        decorations: List<Decoration>,
        target: DecorationTarget?,
    ) {
        // The toolkit would see no change either, and `EpubNavigator` sends them again on every page load.
        if (groups[group] == decorations) return
        groups[group] = decorations
        unsent[group] = unsent[group] == true || decorations.isEmpty()
        if (target != null) sendUnsent(target)
    }

    /** Gives every group to a navigator that has just been created. */
    suspend fun attachTo(target: DecorationTarget) {
        groups.keys.forEach { unsent.putIfAbsent(it, false) }
        sendUnsent(target)
    }

    /** Sends what is still to send, if nothing is loading. Called again whenever a page loads or changes. */
    suspend fun sendUnsent(target: DecorationTarget) =
        sending.withLock {
            // A group at a time, looking again before each: a resource can start loading while one is out.
            while (unsent.isNotEmpty() && !target.isLoadingResources()) {
                val group = unsent.keys.first()
                val clearFirst = unsent.remove(group) == true
                val decorations = groups.getValue(group)
                if (clearFirst && decorations.isNotEmpty()) target.applyDecorations(emptyList(), group)
                target.applyDecorations(decorations, group)
            }
        }
}

/**
 * Whether one of these pages is still loading. kotlin-toolkit keeps a resource's web view invisible
 * until the resource has loaded, and shows it right before telling so (`R2EpubPageFragment`). A page
 * without a view has not started loading: what is recorded will be painted when it does.
 */
internal fun anyPageLoading(pages: List<View?>): Boolean =
    pages.any { page -> page?.findWebView()?.let { it.visibility != View.VISIBLE } ?: false }

private fun View.findWebView(): WebView? =
    when (this) {
        is WebView -> this
        is ViewGroup -> (0 until childCount).firstNotNullOfOrNull { getChildAt(it).findWebView() }
        else -> null
    }
