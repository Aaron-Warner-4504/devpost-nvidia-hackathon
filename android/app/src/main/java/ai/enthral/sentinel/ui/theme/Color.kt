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
    )

    /** OLED option: pure black canvas, slightly darker lifted layers. Same text colours as [dark]. */
    val trueBlack = dark.copy(
        groupedBackground = 0xFF000000,
        background = 0xFF000000,
        elevated = 0xFF131416,
        elevatedSecondary = 0xFF1E2023,
        separator = 0xFF222427,
        accentSoft = 0xFF0F2A33,
    )
}

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
)

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
