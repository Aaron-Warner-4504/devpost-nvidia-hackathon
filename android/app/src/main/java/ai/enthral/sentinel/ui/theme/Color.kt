package ai.enthral.sentinel.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import ai.enthral.sentinel.domain.Tier

/**
 * Tier colour pair as raw ARGB so it can be contrast-checked in plain unit tests.
 * Every pair is >= 4.5:1 (see TierPaletteTest). Colour is never the only signal: TierBadge adds icon + label.
 */
data class TierColorPair(val content: Long, val container: Long)

object TierPalette {
    val light = mapOf(
        Tier.Info to TierColorPair(0xFF0B4F7A, 0xFFE3F1FA),
        Tier.Caution to TierColorPair(0xFF6B4200, 0xFFFFF0CF),
        Tier.High to TierColorPair(0xFF9A3412, 0xFFFFE6D9),
        Tier.Critical to TierColorPair(0xFFA0001C, 0xFFFFDADF),
    )
    val dark = mapOf(
        Tier.Info to TierColorPair(0xFFA8D4F2, 0xFF12344A),
        Tier.Caution to TierColorPair(0xFFF6CC6F, 0xFF4A3500),
        Tier.High to TierColorPair(0xFFFFB899, 0xFF5A2400),
        Tier.Critical to TierColorPair(0xFFFFB4BF, 0xFF5E0A1B),
    )
}

internal val LightColors: ColorScheme = lightColorScheme(
    primary = Color(0xFF0F5C78),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFCDEBF7),
    onPrimaryContainer = Color(0xFF00323F),
    secondary = Color(0xFF496270),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFD0E4F0),
    onSecondaryContainer = Color(0xFF04202C),
    tertiary = Color(0xFF3F6653),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFC1ECD3),
    onTertiaryContainer = Color(0xFF002114),
    background = Color(0xFFF6FAFC),
    onBackground = Color(0xFF12191D),
    surface = Color(0xFFF6FAFC),
    onSurface = Color(0xFF12191D),
    surfaceVariant = Color(0xFFDCE4E9),
    onSurfaceVariant = Color(0xFF40484C),
    outline = Color(0xFF70787D),
    outlineVariant = Color(0xFFC0C8CD),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF0F4F7),
    surfaceContainer = Color(0xFFEAEFF2),
    surfaceContainerHigh = Color(0xFFE4E9EC),
    surfaceContainerHighest = Color(0xFFDEE3E6),
)

internal val DarkColors: ColorScheme = darkColorScheme(
    primary = Color(0xFF8FD0EC),
    onPrimary = Color(0xFF003545),
    primaryContainer = Color(0xFF0B4A60),
    onPrimaryContainer = Color(0xFFCDEBF7),
    secondary = Color(0xFFB4CAD6),
    onSecondary = Color(0xFF0E2A36),
    secondaryContainer = Color(0xFF2F4A57),
    onSecondaryContainer = Color(0xFFD0E4F0),
    tertiary = Color(0xFFA5D0B8),
    onTertiary = Color(0xFF0B3724),
    tertiaryContainer = Color(0xFF254E3A),
    onTertiaryContainer = Color(0xFFC1ECD3),
    background = Color(0xFF0F1417),
    onBackground = Color(0xFFDFE3E7),
    surface = Color(0xFF0F1417),
    onSurface = Color(0xFFDFE3E7),
    surfaceVariant = Color(0xFF40484C),
    onSurfaceVariant = Color(0xFFC0C8CD),
    outline = Color(0xFF8A9296),
    outlineVariant = Color(0xFF40484C),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    surfaceContainerLowest = Color(0xFF0A0F11),
    surfaceContainerLow = Color(0xFF171C1F),
    surfaceContainer = Color(0xFF1B2023),
    surfaceContainerHigh = Color(0xFF262B2E),
    surfaceContainerHighest = Color(0xFF313639),
)
