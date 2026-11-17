package dk.nota.flutterreadium

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * `applyDecorations` hands each decoration over as a JSON string, so this is the
 * path every decoration takes on Android. The `noteMark` glyph used to be dropped
 * here: the style map was rebuilt with `style`, `tint` and `isActive` only, and the
 * template drew the default bullet no matter what the app asked for.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
internal class DecorationFromJsonTest {
    private fun decoration(style: String) =
        decorationFromJson(
            """{"id":"a","locator":{"href":"OEBPS/c1.xhtml","type":"application/xhtml+xml"},"style":$style}""",
        )

    @Test
    fun noteMarkKeepsTheGlyphTheAppAskedFor() {
        val style = decoration("""{"style":"noteMark","tint":"#A5D6A7","isActive":false,"mark":"📝"}""")?.style

        assertTrue(style is NoteMarkStyle)
        assertEquals("📝", (style as NoteMarkStyle).glyph)
    }

    @Test
    fun noteMarkWithoutAGlyphFallsBackToTheBullet() {
        val style = decoration("""{"style":"noteMark","tint":"#A5D6A7","isActive":false}""")?.style

        assertEquals(DEFAULT_NOTE_MARK_GLYPH, (style as NoteMarkStyle).glyph)
    }
}
