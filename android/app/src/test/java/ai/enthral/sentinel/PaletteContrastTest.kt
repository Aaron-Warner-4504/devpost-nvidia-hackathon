package ai.enthral.sentinel

import ai.enthral.sentinel.ui.theme.Palette
import ai.enthral.sentinel.ui.theme.Palettes
import ai.enthral.sentinel.ui.theme.SystemGlassConditions
import ai.enthral.sentinel.ui.theme.Tinted
import ai.enthral.sentinel.ui.theme.decideGlass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    // ---- Glass (pass 2): composites against the worst-case backdrop -------------------------------------------

    /** Source-over: [fg] at [alpha] on an opaque [bg]. */
    private fun over(fg: Long, alpha: Float, bg: Long): Long {
        fun ch(c: Long, shift: Int) = ((c shr shift) and 0xFF).toDouble()
        fun mix(shift: Int) = Math.round(ch(fg, shift) * alpha + ch(bg, shift) * (1 - alpha)).toLong()
        return 0xFF000000 or (mix(16) shl 16) or (mix(8) shl 8) or mix(0)
    }
    private fun Tinted.over(bg: Long) = over(color, alpha, bg)

    /** Every point of the atmosphere: base, each blob at full strength, every overlap. */
    private fun atmosphere(p: Palette): List<Long> {
        val g = p.glass
        val singles = g.blobs.map { it.over(g.base) }
        val pairs = g.blobs.indices.flatMap { i -> (i + 1 until g.blobs.size).map { j -> g.blobs[j].over(g.blobs[i].over(g.base)) } }
        val all = g.blobs.fold(g.base) { acc, b -> b.over(acc) }
        return listOf(g.base) + singles + pairs + all
    }

    @Test fun `text directly on the atmosphere meets 4_5 to 1 at every blob overlap`() {
        for ((mode, p) in modes) {
            atmosphere(p).forEach { bg ->
                listOf("label" to p.label, "secondary" to p.secondaryLabel, "accent" to p.accent).forEach { (n, c) ->
                    check(mode, "$n on atmosphere", c, bg, 4.5)
                }
                p.tiers.forEach { (t, pair) -> check(mode, "$t glyph on atmosphere", pair.content, bg, 3.0) }
            }
        }
    }

    @Test fun `all text on glass cards meets 4_5 to 1 over the worst backdrop, with and without the sheen`() {
        for ((mode, p) in modes) {
            atmosphere(p).forEach { atmos ->
                val card = p.glass.card.over(atmos)
                listOf(card, p.glass.sheen.over(card)).forEach { bg ->
                    p.textColors.forEach { fg -> check(mode, "text on glass card", fg, bg, 4.5) }
                }
            }
        }
    }

    @Test fun `bar text meets 4_5 to 1 even with solid content scrolling beneath the blur`() {
        for ((mode, p) in modes) {
            // Blur averages what is beneath; the extreme is a solid patch of any content colour.
            val beneath = atmosphere(p) + listOf(p.accent, p.label, p.elevated, p.groupedBackground, p.accentSoft) +
                p.tiers.values.flatMap { listOf(it.content, it.container) }
            beneath.forEach { under ->
                val bar = p.glass.bar.over(under)
                listOf("label" to p.label, "secondary (tab labels)" to p.secondaryLabel, "accent (selected tab)" to p.accent)
                    .forEach { (n, c) -> check(mode, "$n on bar over ${under.toString(16)}", c, bar, 4.5) }
            }
        }
    }

    @Test fun `sheets are nearly opaque glass and every text colour passes over black or white beneath`() {
        for ((mode, p) in modes) {
            listOf(0xFF000000, 0xFFFFFFFF, p.accent).forEach { under ->
                val sheet = p.glass.sheet.over(under)
                p.textColors.forEach { fg -> check(mode, "text on sheet", fg, sheet, 4.5) }
            }
        }
    }

    @Test fun `glass falls back to opaque for every system or user reason`() {
        val ok = SystemGlassConditions(animationsOff = false, highContrastText = false, powerSave = false, lowRam = false, blurCapable = true)
        val on = decideGlass(false, ok)
        assertTrue(on.glass && on.blur && on.motion)
        assertFalse(decideGlass(true, ok).glass)
        listOf(ok.copy(animationsOff = true), ok.copy(highContrastText = true), ok.copy(powerSave = true), ok.copy(lowRam = true))
            .forEach { assertEquals(false, decideGlass(false, it).glass) }
        val oldApi = decideGlass(false, ok.copy(blurCapable = false))
        assertTrue(oldApi.glass && !oldApi.blur)
    }
}
