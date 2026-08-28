import Foundation
import ReadiumNavigator
import ReadiumShared
import UIKit

/// Decoration application, decoration-interaction observation, the custom
/// "highlight current selection" editing action, and the spotlight decoration
/// template used for TTS utterance highlighting.
extension EPUBReaderView {

  public func applyDecorations(_ decorations: [Decoration], forGroup groupIdentifier: String) {
    Log.reader.debug("applyDecorations: \(decorations) identifier: \(groupIdentifier)")
    ensureDecorationObservation(forGroup: groupIdentifier)
    self.readiumViewController.apply(decorations: decorations, in: groupIdentifier)
  }

  @objc public func onCustomEditingAction() {
    Log.reader.debug("onCustomEditingAction")
    // NOTE: This method will not actually be hit. It will try to find an "onCustomEditingAction" function in the Responder chain!
    // Because of how Flutter generates its responder chain, we need to implement this func in the client AppDelegate.swift and then call back into the plugin from there.
    // see https://github.com/readium/swift-toolkit/issues/466

    if let selection = readiumViewController.currentSelection {
      let selectionLocator = selection.locator
      readiumViewController.apply(decorations: [Decoration(id: "highlight", locator: selectionLocator, style: .highlight(), userInfo: [:])], in: "user-highlight")
      readiumViewController.clearSelection()
    }
  }

  /// Called when a decoration is tapped/activated by the user.
  func onDecorationActivated(event: OnDecorationActivatedEvent) {
    Log.reader.debug("onDecorationActivated: \(event.decoration.id) in group \(event.group)")
    channel.onDecorationInteraction(
      decorationId: event.decoration.id,
      group: event.group,
      type: "tap",
      locator: event.decoration.locator
    )
  }

  /// Registers decoration interaction observation for a group.
  /// Called when `applyDecorations` is used with a new group identifier.
  private func ensureDecorationObservation(forGroup group: String) {
    guard !observedDecorationGroups.contains(group) else { return }
    observedDecorationGroups.insert(group)
    readiumViewController.observeDecorationInteractions(inGroup: group) { [weak self] event in
      self?.onDecorationActivated(event: event)
    }
  }

  // MARK: – Custom decoration templates

  /// Escape a string for use as an HTML attribute value (double-quoted).
  private static func escapeHtmlAttr(_ s: String) -> String {
    s.replacingOccurrences(of: "&", with: "&amp;")
     .replacingOccurrences(of: "\"", with: "&quot;")
     .replacingOccurrences(of: "<", with: "&lt;")
     .replacingOccurrences(of: ">", with: "&gt;")
  }

  /// Spotlight: semi-transparent tinted box over the active text range, one per
  /// text line.
  ///
  /// Uses `.boxes` layout (one `<div>` per CSS border box / text line) so that
  /// utterances spanning CSS columns are represented by per-line boxes each
  /// contained within their own column. `.bounds` would produce a single rectangle
  /// spanning the bounding box of the whole range, which overflows across the gutter
  /// and into the next column when an utterance crosses a column boundary.
  ///
  /// The class `flutter-readium-spotlight` is a stable marker that
  /// `flutterReadiumTools.js` watches via MutationObserver to:
  ///   1. Toggle `body.flutter-readium-spotlight-active`, which fades all body text
  ///      to low contrast via an injected CSS rule.
  ///   2. Read `data-css-selector` and add `.flutter-readium-spotlit-text` to the
  ///      matching element, so a higher-specificity CSS rule restores its text colour.
  ///
  /// `background-color` MUST be `!important`: Readium CSS forces every element's
  /// background to transparent when a custom theme is active (see Gotcha in
  /// AGENTS.md); without `!important` the fill would be invisible.
  ///
  /// `z-index: -1` renders the fill behind the text glyphs (same as the upstream
  /// highlight/underline templates). This keeps the text colour
  /// visually unaffected by the tint overlay and matches what `::highlight()` does
  /// on web — the yellow acts purely as a background, not a colour wash.
  static func spotlightDecorationTemplate() -> HTMLDecorationTemplate {
    HTMLDecorationTemplate(
      layout: .boxes,
      width: .bounds,
      element: { decoration in
        let config = decoration.style.config as! Decoration.Style.HighlightConfig
        let bgColor = config.tint.map { "\($0.cssValue(alpha: 0.5))" } ?? "transparent"
        let sel = Self.escapeHtmlAttr(decoration.locator.locations.cssSelector ?? "")
        let hl  = Self.escapeHtmlAttr(decoration.locator.text.highlight ?? "")
        let bef = Self.escapeHtmlAttr(decoration.locator.text.before ?? "")
        return "<div class=\"flutter-readium-spotlight\" data-css-selector=\"\(sel)\" data-text-highlight=\"\(hl)\" data-text-before=\"\(bef)\" data-tint=\"\(bgColor)\" style=\"z-index: -1; box-sizing: border-box;\"/>"
      }
    )
  }

  /// Note mark: a small glyph pinned to the **first character** of the range, so
  /// a reader can tell an annotation carries a written note without opening it.
  ///
  /// Draws no box of its own — it is meant to sit on top of a `highlight` or
  /// `underline` covering the same range.
  ///
  /// `.wrap` width, not `.bounds`: `.wrap` makes each box hug the text on its
  /// own line, so the first box's left edge **is** the first character. `.bounds`
  /// returns the bounding rectangle of the whole range, whose left edge is the
  /// column margin — a range starting mid-line would get its mark dragged back
  /// to the start of that line.
  ///
  /// `.boxes` layout means this element is cloned **once per line**, so the glyph
  /// would repeat down the whole range. `FlutterReadiumTools.scss` hides every
  /// box after the first with a sibling combinator; that works because Readium's
  /// decorator puts the boxes of one decoration inside that decoration's own
  /// container `<div>`, so the combinator cannot reach a different annotation.
  ///
  /// **The mark is tappable.** Readium's decorator resolves a tap by comparing the
  /// point against the bounding rect of each clickable element, so the badge has
  /// to sit *inside* the decoration's own box — which it does, because it is
  /// pinned over the first character rather than beside it. `data-activable="1"`
  /// narrows the hit area to the badge itself; without it Readium falls back to
  /// the box, and the whole first line would answer.
  ///
  /// A tap arrives with this decoration's id, which carries a suffix. The Dart
  /// side strips it back to the annotation's own id.
  static func noteMarkDecorationTemplate() -> HTMLDecorationTemplate {
    HTMLDecorationTemplate(
      layout: .boxes,
      width: .wrap,
      // Escrito a propósito de forma aburrida —variables con tipo explícito, sin
      // cadenas opcionales ni literales de array interpolados—. El comprobador de
      // tipos de Swift se cae con "Failed to produce diagnostic for expression"
      // ante expresiones así, y el error no señala la causa.
      element: { decoration in
        let config: NoteMarkConfig? = decoration.style.config as? NoteMarkConfig
        let tint: UIColor? = config?.tint
        // Semitransparente a propósito: el círculo se monta sobre la letra y
        // tiene que dejarla leer.
        var background: String = "rgba(0, 0, 0, 0.55)"
        if let tint = tint {
          background = "\(tint.cssValue(alpha: 0.8))"
        }
        let glyph: String = Self.escapeHtmlAttr(config?.glyph ?? defaultNoteMarkGlyph)

        // Solo el color va aquí; la forma vive en FlutterReadiumTools.scss para
        // no escribir el mismo CSS otra vez en Kotlin.
        // `!important` es obligatorio: ReadiumCSS fuerza a transparente el fondo
        // de todo elemento cuando hay un tema activo.
        var html: String = "<div class=\"flutter-readium-note-mark\" style=\"box-sizing: border-box;\">"
        html += "<span class=\"flutter-readium-note-mark__badge\" data-activable=\"1\" "
        html += "style=\"background-color: " + background + " !important;\">"
        html += glyph + "</span></div>"
        return html
      }
    )
  }
}
