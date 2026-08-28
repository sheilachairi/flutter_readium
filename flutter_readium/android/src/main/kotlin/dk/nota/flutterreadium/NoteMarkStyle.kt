package dk.nota.flutterreadium

import android.os.Parcelable
import androidx.annotation.ColorInt
import kotlinx.parcelize.Parcelize
import org.readium.r2.navigator.Decoration

/**
 * Note mark: a small glyph pinned to the first character of the range, so a
 * reader can tell an annotation carries a written note without opening it.
 *
 * Paints no box of its own — it is meant to be applied on top of a Highlight or
 * Underline covering the same range.
 */
@Parcelize
data class NoteMarkStyle(
    @ColorInt override val tint: Int,
    val glyph: String,
) : Decoration.Style,
    Decoration.Style.Tinted
