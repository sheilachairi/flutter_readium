package dk.nota.flutterreadium

import android.view.View
import android.webkit.WebView
import android.widget.FrameLayout
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.readium.r2.navigator.Decoration
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

private const val HIGHLIGHTS = "highlights"
private const val FOCUS = "focus"

private val Decoration.href get() = locator.href.toString()

/**
 * Reopening a book on Android left the page it reopened at without its decorations, every few
 * times. kotlin-toolkit had dropped their script: it arrived while that resource was finishing
 * its load, and the toolkit, which had already recorded them as applied, never sent it again.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
internal class AppliedDecorationsTest {
    /**
     * kotlin-toolkit 3.4.0 as far as decorations go. It records them, works out the changes off the
     * main thread, and sends each resource its script only if the resource is loaded. A resource
     * shows what is recorded when its HTML finishes, and counts as loaded a moment later.
     */
    private class Toolkit : DecorationTarget {
        private enum class Resource { CREATED, FINISHED, LOADED }

        private val resources = mutableMapOf<String, Resource>()
        private val recorded = mutableMapOf<String, List<Decoration>>()

        /** What each resource shows, by group, in the order it was added: a duplicate shows. */
        private val showing = mutableMapOf<String, MutableMap<String, MutableList<Decoration>>>()

        var sends = 0
            private set

        fun shown(
            href: String,
            group: String = HIGHLIGHTS,
        ): List<Decoration> = showing[href]?.get(group).orEmpty()

        fun create(vararg hrefs: String) = hrefs.forEach { resources[it] = Resource.CREATED }

        /** `onResourceLoaded`: the resource gets what is recorded for it. */
        fun finish(vararg hrefs: String) =
            hrefs.forEach { href ->
                resources[href] = Resource.FINISHED
                showing[href] =
                    recorded
                        .mapValues { (_, decorations) -> decorations.filterTo(mutableListOf()) { it.href == href } }
                        .toMutableMap()
            }

        fun load(vararg hrefs: String) = hrefs.forEach { resources[it] = Resource.LOADED }

        override suspend fun applyDecorations(
            decorations: List<Decoration>,
            group: String,
        ) {
            sends++
            val before = recorded[group].orEmpty()
            recorded[group] = decorations
            if (decorations.isEmpty()) {
                loaded().forEach { showing[it]?.remove(group) }
                return
            }
            // Where the toolkit works out the changes, off the main thread.
            yield()
            // As `changesByHref`: by id, and a change goes only to the resource the decoration is in now.
            val removed = before.filter { old -> decorations.none { it.id == old.id } }
            val changed = decorations.filter { new -> before.any { it.id == new.id && it != new } }
            val added = decorations.filter { new -> before.none { it.id == new.id } }
            loaded().forEach { href ->
                val shown = showing.getOrPut(href) { mutableMapOf() }.getOrPut(group) { mutableListOf() }
                fun remove(id: String) = shown.indexOfFirst { it.id == id }.takeIf { it >= 0 }?.let(shown::removeAt)
                removed.filter { it.href == href }.forEach { remove(it.id) }
                changed.filter { it.href == href }.forEach {
                    remove(it.id)
                    shown.add(it)
                }
                added.filter { it.href == href }.forEach(shown::add)
            }
        }

        override fun isLoadingResources() = resources.values.any { it != Resource.LOADED }

        private fun loaded() = resources.filterValues { it == Resource.LOADED }.keys
    }

    private fun decoration(
        id: String,
        href: String,
        tint: String = "#A5D6A7",
    ) = requireNotNull(
        decorationFromJson(
            """{"id":"$id","locator":{"href":"$href","type":"application/xhtml+xml"},""" +
                """"style":{"style":"highlight","tint":"$tint","isActive":false}}""",
        ),
    )

    private val a5 = decoration("a", "c5.xhtml")
    private val b6 = decoration("b", "c6.xhtml")
    private val c7 = decoration("c", "c7.xhtml")
    private val d6 = decoration("d", "c6.xhtml")

    private val decorations = AppliedDecorations()
    private val toolkit = Toolkit()

    private fun loadedPage(href: String) {
        toolkit.create(href)
        toolkit.finish(href)
        toolkit.load(href)
    }

    @Test
    fun thePageThatWasFinishingItsLoadGetsItsDecorationsOnceItLoads() =
        runBlocking {
            loadedPage("c5.xhtml")
            toolkit.create("c6.xhtml")
            toolkit.finish("c6.xhtml")

            decorations.show(HIGHLIGHTS, listOf(a5, b6), toolkit)
            toolkit.load("c6.xhtml")
            decorations.sendUnsent(toolkit)

            assertEquals(listOf(a5), toolkit.shown("c5.xhtml"))
            assertEquals(listOf(b6), toolkit.shown("c6.xhtml"))
        }

    @Test
    fun aDecorationRemovedWhileItsPageWasFinishingItsLoadIsGoneOnceItLoads() =
        runBlocking {
            decorations.show(HIGHLIGHTS, listOf(c7), toolkit)
            toolkit.create("c7.xhtml")
            toolkit.finish("c7.xhtml")

            decorations.show(HIGHLIGHTS, emptyList(), toolkit)
            toolkit.load("c7.xhtml")
            decorations.sendUnsent(toolkit)

            assertEquals(emptyList<Decoration>(), toolkit.shown("c7.xhtml"))
        }

    @Test
    fun aDecorationChangedWhileItsPageWasFinishingItsLoadShowsChangedOnceItLoads() =
        runBlocking {
            val recoloured = decoration("c", "c7.xhtml", tint = "#FFE082")
            decorations.show(HIGHLIGHTS, listOf(c7), toolkit)
            toolkit.create("c7.xhtml")
            toolkit.finish("c7.xhtml")

            decorations.show(HIGHLIGHTS, listOf(recoloured), toolkit)
            toolkit.load("c7.xhtml")
            decorations.sendUnsent(toolkit)

            assertEquals(listOf(recoloured), toolkit.shown("c7.xhtml"))
        }

    @Test
    fun aGroupWaitsIfAPageStartsLoadingWhileTheOneBeforeIsOut() =
        runBlocking {
            loadedPage("c6.xhtml")
            decorations.show(HIGHLIGHTS, listOf(b6), target = null)
            decorations.show(FOCUS, listOf(c7), target = null)

            listOf(
                launch { decorations.attachTo(toolkit) },
                // While the highlights are out, the next chapter finishes its HTML.
                launch {
                    toolkit.create("c7.xhtml")
                    toolkit.finish("c7.xhtml")
                },
            ).joinAll()
            toolkit.load("c7.xhtml")
            decorations.sendUnsent(toolkit)

            assertEquals(listOf(b6), toolkit.shown("c6.xhtml"))
            assertEquals(listOf(c7), toolkit.shown("c7.xhtml", FOCUS))
        }

    @Test
    fun decorationsMovedToAnotherPageWhileOneLoadsLeaveNothingBehind() =
        runBlocking {
            val before = decoration("t", "c6.xhtml")
            val moved = decoration("t", "c7.xhtml")
            loadedPage("c6.xhtml")
            decorations.show(FOCUS, listOf(before), toolkit)
            toolkit.create("c7.xhtml")

            // What `EpubNavigator` does to move a decoration to another resource.
            decorations.show(FOCUS, emptyList(), toolkit)
            decorations.show(FOCUS, listOf(moved), toolkit)
            toolkit.finish("c7.xhtml")
            toolkit.load("c7.xhtml")
            decorations.sendUnsent(toolkit)

            assertEquals(emptyList<Decoration>(), toolkit.shown("c6.xhtml", FOCUS))
            assertEquals(listOf(moved), toolkit.shown("c7.xhtml", FOCUS))
        }

    @Test
    fun aPageLoadWithNothingWaitingSendsNothing() =
        runBlocking {
            loadedPage("c6.xhtml")
            decorations.show(HIGHLIGHTS, listOf(b6), toolkit)
            val sends = toolkit.sends

            // The next chapter loads while reading: the page in front is not touched, so it cannot blink.
            loadedPage("c7.xhtml")
            decorations.sendUnsent(toolkit)

            assertEquals(sends, toolkit.sends)
            assertEquals(listOf(b6), toolkit.shown("c6.xhtml"))
        }

    @Test
    fun theSameDecorationsAgainAreNotSent() =
        runBlocking {
            loadedPage("c6.xhtml")
            decorations.show(HIGHLIGHTS, listOf(b6), toolkit)
            val sends = toolkit.sends

            // `EpubNavigator` sends them again on every page load.
            decorations.show(HIGHLIGHTS, listOf(decoration("b", "c6.xhtml")), toolkit)

            assertEquals(sends, toolkit.sends)
        }

    @Test
    fun aSendWaitsForTheOneOutSoTheyLandInOrder() =
        runBlocking {
            loadedPage("c6.xhtml")

            listOf(
                launch { decorations.show(HIGHLIGHTS, listOf(b6, d6), toolkit) },
                // Emptying has no changes to work out: without waiting, it would land first.
                launch { decorations.show(HIGHLIGHTS, emptyList(), toolkit) },
            ).joinAll()

            assertEquals(emptyList<Decoration>(), toolkit.shown("c6.xhtml"))
        }

    @Test
    fun aNavigatorCreatedLaterGetsTheDecorationsBeforeAnyPageLoads() =
        runBlocking {
            decorations.show(HIGHLIGHTS, listOf(b6), target = null)

            decorations.attachTo(toolkit)
            toolkit.create("c6.xhtml")
            toolkit.finish("c6.xhtml")

            assertEquals(listOf(b6), toolkit.shown("c6.xhtml"))
        }

    @Test
    fun aPageIsLoadingWhileItsWebViewIsHidden() {
        val context = RuntimeEnvironment.getApplication()

        fun page(visibility: Int) =
            FrameLayout(context).apply { addView(WebView(context).apply { this.visibility = visibility }) }

        assertTrue(anyPageLoading(listOf(page(View.VISIBLE), page(View.INVISIBLE))))
        assertFalse(anyPageLoading(listOf(page(View.VISIBLE), page(View.VISIBLE))))
        // A page whose view does not exist yet will paint what is recorded when it loads.
        assertFalse(anyPageLoading(listOf(page(View.VISIBLE), null)))
        assertTrue(anyPageLoading(listOf(FrameLayout(context).apply { addView(page(View.INVISIBLE)) })))
    }

    /**
     * What [AppliedDecorations] works around is how kotlin-toolkit sends decorations, read in the
     * version it names. Another version may send them otherwise, or keep its web views visible while
     * they load, and the waiting would stop without a single error. Read it again, measure it (the
     * patch for the decorations of a page finishing its load, in Mundibook's `CLAUDE.md`), and then
     * update [DECORATIONS_READ_AGAINST_TOOLKIT].
     */
    @Test
    fun theToolkitIsTheOneTheDecorationsWereReadIn() {
        val version = Regex("""ext\.readium_version\s*=\s*'([^']+)'""").find(File("build.gradle").readText())

        assertEquals(DECORATIONS_READ_AGAINST_TOOLKIT, version?.groupValues?.get(1))
    }
}
