package ai.enthral.sentinel

import ai.enthral.sentinel.ui.theme.Palette
import ai.enthral.sentinel.ui.theme.Palettes
import kotlin.math.pow
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WCAG contrast for the redesigned palette (docs/design.md): every text colour on every surface it can sit on is
 * >= 4.5:1, and every UI boundary / glyph is >= 3:1, in light, dark and true-black modes.
 */
class PaletteContrastTest {
    private val modes = listOf("light" to Palettes.light, "dark" to Palettes.dark, "trueBlack" to Palettes.trueBlack)

    private fun lum(argb: Long): Double {
        fun ch(shift: Int): Double {
            val c = ((argb shr shift) and 0xFF) / 255.0
            return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * ch(16) + 0.7152 * ch(8) + 0.0722 * ch(0)
    }

    private fun contrast(a: Long, b: Long): Double {
        val (x, y) = lum(a) to lum(b)
        return (maxOf(x, y) + 0.05) / (minOf(x, y) + 0.05)
    }

    private fun check(mode: String, what: String, fg: Long, bg: Long, min: Double) {
        val c = contrast(fg, bg)
        assertTrue("$mode: $what = ${"%.2f".format(c)} < $min (fg ${fg.toString(16)}, bg ${bg.toString(16)})", c >= min)
    }

    @Test fun `all text colours meet 4_5 to 1 on every surface`() {
        for ((mode, p) in modes) {
            p.textColors.forEach { fg -> p.textBackgrounds.forEach { bg -> check(mode, "text on surface", fg, bg, 4.5) } }
        }
    }

    @Test fun `text on accent, tinted and tier washes meets 4_5 to 1`() {
        for ((mode, p) in modes) {
            check(mode, "onAccent on accent (primary button)", p.onAccent, p.accent, 4.5)
            check(mode, "accent on accentSoft (secondary button, chips)", p.accent, p.accentSoft, 4.5)
            check(mode, "label on accentSoft", p.label, p.accentSoft, 4.5)
            p.tiers.forEach { (tier, pair) ->
                check(mode, "$tier content on its container (badge, timeline time)", pair.content, pair.container, 4.5)
                check(mode, "label on $tier container (current timeline event)", p.label, pair.container, 4.5)
                check(mode, "secondary label on $tier container", p.secondaryLabel, pair.container, 4.5)
            }
            // Inverse snackbar: background-coloured text on a label-coloured bar.
            check(mode, "snackbar text", p.groupedBackground, p.label, 4.5)
            val action = if (p.isDark) Palettes.light.accent else Palettes.dark.accent
            check(mode, "snackbar action", action, p.label, 4.5)
        }
    }

    @Test fun `ui boundaries and glyphs meet 3 to 1`() {
        for ((mode, p) in modes) {
            listOf(p.groupedBackground, p.background, p.elevated).forEach { bg ->
                check(mode, "accent control (switch on, selected tab, button)", p.accent, bg, 3.0)
                check(mode, "unchecked switch border / thumb", p.tertiaryLabel, bg, 3.0)
                p.tiers.forEach { (tier, pair) -> check(mode, "$tier glyph", pair.content, bg, 3.0) }
            }
            check(mode, "unselected tab icon", p.secondaryLabel, p.elevated, 3.0)
        }
    }

    @Test fun `true black is pure black and otherwise matches dark text colours`() {
        val t: Palette = Palettes.trueBlack
        assertTrue(t.groupedBackground == 0xFF000000 && t.background == 0xFF000000)
        assertTrue(t.label == Palettes.dark.label && t.accent == Palettes.dark.accent)
    }
}
