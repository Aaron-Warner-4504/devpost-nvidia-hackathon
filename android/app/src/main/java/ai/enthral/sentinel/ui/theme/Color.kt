package ai.enthral.sentinel.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import ai.enthral.sentinel.domain.Tier

/**
 * Tier colour pair as raw ARGB so it can be contrast-checked in plain unit tests.
 * Every pair is >= 4.5:1 (see TierTest / PaletteContrastTest). Colour is never the only signal: tier glyphs have
 * their own shape and a text label.
 */
data class TierColorPair(val content: Long, val container: Long)

object TierPalette {
    val light = mapOf(
        Tier.Info to TierColorPair(0xFF2B6A4C, 0xFFE5F1EA),
        Tier.Caution to TierColorPair(0xFF7A5100, 0xFFF9EFD9),
        Tier.High to TierColorPair(0xFF9A3B12, 0xFFFAE8DF),
        Tier.Critical to TierColorPair(0xFFA8172F, 0xFFFBE3E6),
    )
    val dark = mapOf(
        Tier.Info to TierColorPair(0xFF8DD0AE, 0xFF14281E),
        Tier.Caution to TierColorPair(0xFFEDC36C, 0xFF2C2311),
        Tier.High to TierColorPair(0xFFF4A684, 0xFF33190E),
        Tier.Critical to TierColorPair(0xFFFF9DAA, 0xFF3A1219),
    )
}

/**
 * The neutral + accent system (see docs/design.md). Raw ARGB so `PaletteContrastTest` can check every
 * text/background pair without Android.
 */
data class Palette(
    val isDark: Boolean,
    val groupedBackground: Long,
    val background: Long,
    val elevated: Long,
    val elevatedSecondary: Long,
    val label: Long,
    val secondaryLabel: Long,
    val tertiaryLabel: Long,
    val separator: Long,
    val accent: Long,
    val onAccent: Long,
    val accentSoft: Long,
    val tiers: Map<Tier, TierColorPair>,
    /** Glass mode: the living background and the translucent layers on top of it (docs/design.md, Glass). */
    val glass: GlassTokens,
) {
    /** Every background text can sit on. */
    val textBackgrounds: List<Long> get() = listOf(groupedBackground, background, elevated, elevatedSecondary)

    /** Every text colour used on [textBackgrounds]. Tier content is used as text in badges and the timeline. */
    val textColors: List<Long> get() = listOf(label, secondaryLabel, tertiaryLabel, accent) + tiers.values.map { it.content }
}

object Palettes {
    val light = Palette(
        isDark = false,
        groupedBackground = 0xFFF2F3F5,
        background = 0xFFFBFBFC,
        elevated = 0xFFFFFFFF,
        elevatedSecondary = 0xFFF6F7F9,
        label = 0xFF101216,
        secondaryLabel = 0xFF565C66,
        tertiaryLabel = 0xFF676D77,
        separator = 0xFFE3E5E9,
        accent = 0xFF0C5A73,
        onAccent = 0xFFFFFFFF,
        accentSoft = 0xFFE4EFF3,
        tiers = TierPalette.light,
        glass = LightGlass,
    )
    val dark = Palette(
        isDark = true,
        groupedBackground = 0xFF0B0C0E,
        background = 0xFF0B0C0E,
        elevated = 0xFF1A1C1F,
        elevatedSecondary = 0xFF24272B,
        label = 0xFFF2F3F5,
        secondaryLabel = 0xFFA3A9B2,
        tertiaryLabel = 0xFF8C929B,
        separator = 0xFF2B2E33,
        accent = 0xFF71C4DA,
        onAccent = 0xFF062530,
        accentSoft = 0xFF12303A,
        tiers = TierPalette.dark,
        glass = DarkGlass,
    )

    /** OLED option: pure black canvas, slightly darker lifted layers. Same text colours as [dark]. */
    val trueBlack = dark.copy(
        groupedBackground = 0xFF000000,
        background = 0xFF000000,
        elevated = 0xFF131416,
        elevatedSecondary = 0xFF1E2023,
        separator = 0xFF222427,
        accentSoft = 0xFF0F2A33,
        glass = TrueBlackGlass,
    )
}

/** A colour at a given opacity (0..1), kept separate so contrast tests can composite it. */
data class Tinted(val color: Long, val alpha: Float)

/**
 * Atmosphere: an opaque [base] with soft radial [blobs] (colour at full alpha in the centre, fading out).
 * [card] / [bar] / [sheet] are glass tints, composited over whatever is behind them. Every text colour must pass
 * 4.5:1 on every composite (PaletteContrastTest): blobs overlapping, and for bars also solid content beneath.
 */
data class GlassTokens(
    val base: Long,
    val blobs: List<Tinted>,
    val card: Tinted,
    val bar: Tinted,
    val sheet: Tinted,
    /** 1-px specular edge at the top-left of glass surfaces (decorative). */
    val edge: Tinted,
    /** Light sheen across the top half of glass cards (included in contrast composites). */
    val sheen: Tinted,
)

private val LightGlass = GlassTokens(
    base = 0xFFEDF1F5,
    blobs = listOf(Tinted(0xFF6FC2D6, 0.26f), Tinted(0xFF9AAEF2, 0.22f), Tinted(0xFFA9DCC4, 0.26f)),
    card = Tinted(0xFFFFFFFF, 0.72f),
    bar = Tinted(0xFFFFFFFF, 0.84f),
    sheet = Tinted(0xFFFFFFFF, 0.98f),
    edge = Tinted(0xFFFFFFFF, 0.90f),
    sheen = Tinted(0xFFFFFFFF, 0.55f),
)

private val DarkGlass = GlassTokens(
    base = 0xFF05080C,
    blobs = listOf(Tinted(0xFF1B7A93, 0.36f), Tinted(0xFF3348A8, 0.30f), Tinted(0xFF5A3A8C, 0.22f)),
    card = Tinted(0xFF12161D, 0.74f),
    bar = Tinted(0xFF0E1217, 0.84f),
    sheet = Tinted(0xFF161A21, 0.98f),
    edge = Tinted(0xFFFFFFFF, 0.26f),
    sheen = Tinted(0xFFFFFFFF, 0.045f),
)

private val TrueBlackGlass = GlassTokens(
    base = 0xFF000000,
    blobs = listOf(Tinted(0xFF1B7A93, 0.26f), Tinted(0xFF3348A8, 0.22f), Tinted(0xFF5A3A8C, 0.16f)),
    card = Tinted(0xFF0E1116, 0.74f),
    bar = Tinted(0xFF0A0C10, 0.84f),
    sheet = Tinted(0xFF111318, 0.98f),
    edge = Tinted(0xFFFFFFFF, 0.22f),
    sheen = Tinted(0xFFFFFFFF, 0.04f),
)

/** Compose-side view of a [Palette]. */
data class SentinelPalette(
    val isDark: Boolean,
    val groupedBackground: Color,
    val background: Color,
    val elevated: Color,
    val elevatedSecondary: Color,
    val label: Color,
    val secondaryLabel: Color,
    val tertiaryLabel: Color,
    val separator: Color,
    val accent: Color,
    val onAccent: Color,
    val accentSoft: Color,
    /** Ink used for the one soft diffuse shadow (never a hard Material shadow). */
    val shadow: Color,
    val atmosphereBase: Color,
    val atmosphereBlobs: List<Color>,
    val glassCard: Color,
    val glassBar: Color,
    val glassSheet: Color,
    val glassEdge: Color,
    val glassSheen: Color,
)

private fun Tinted.toColor() = Color(color).copy(alpha = alpha)

fun Palette.toCompose() = SentinelPalette(
    isDark = isDark,
    groupedBackground = Color(groupedBackground),
    background = Color(background),
    elevated = Color(elevated),
    elevatedSecondary = Color(elevatedSecondary),
    label = Color(label),
    secondaryLabel = Color(secondaryLabel),
    tertiaryLabel = Color(tertiaryLabel),
    separator = Color(separator),
    accent = Color(accent),
    onAccent = Color(onAccent),
    accentSoft = Color(accentSoft),
    shadow = if (isDark) Color.Black.copy(alpha = 0.5f) else Color(0xFF101216).copy(alpha = 0.10f),
    atmosphereBase = Color(glass.base),
    atmosphereBlobs = glass.blobs.map { it.toColor() },
    glassCard = glass.card.toColor(),
    glassBar = glass.bar.toColor(),
    glassSheet = glass.sheet.toColor(),
    glassEdge = glass.edge.toColor(),
    glassSheen = glass.sheen.toColor(),
)

/** Material slots derived from the palette, so stock components (switches, fields, snackbars) match. */
fun Palette.toColorScheme(): ColorScheme {
    val p = toCompose()
    val critical = Color(tiers.getValue(Tier.Critical).content)
    val criticalContainer = Color(tiers.getValue(Tier.Critical).container)
    val base = if (isDark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = p.accent,
        onPrimary = p.onAccent,
        primaryContainer = p.accentSoft,
        onPrimaryContainer = p.label,
        inversePrimary = p.accent,
        secondary = p.secondaryLabel,
        onSecondary = p.elevated,
        secondaryContainer = p.accentSoft,
        onSecondaryContainer = p.label,
        tertiary = p.accent,
        onTertiary = p.onAccent,
        tertiaryContainer = p.accentSoft,
        onTertiaryContainer = p.label,
        background = p.groupedBackground,
        onBackground = p.label,
        surface = p.groupedBackground,
        onSurface = p.label,
        surfaceVariant = p.elevatedSecondary,
        onSurfaceVariant = p.secondaryLabel,
        surfaceTint = Color.Transparent,
        inverseSurface = p.label,
        inverseOnSurface = p.groupedBackground,
        outline = p.tertiaryLabel,
        outlineVariant = p.separator,
        error = critical,
        onError = p.elevated,
        errorContainer = criticalContainer,
        onErrorContainer = critical,
        scrim = Color.Black,
        surfaceBright = p.elevated,
        surfaceDim = p.groupedBackground,
        surfaceContainerLowest = p.elevated,
        surfaceContainerLow = p.elevated,
        surfaceContainer = p.elevated,
        surfaceContainerHigh = p.elevated,
        surfaceContainerHighest = p.elevatedSecondary,
    )
}
