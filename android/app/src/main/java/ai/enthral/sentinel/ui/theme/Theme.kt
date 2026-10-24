package ai.enthral.sentinel.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import ai.enthral.sentinel.domain.Tier

@Immutable
data class TierColors(val content: Color, val container: Color)

@Immutable
class SentinelColors(private val map: Map<Tier, TierColors>) {
    operator fun get(tier: Tier): TierColors = map.getValue(tier)
}

private fun Map<Tier, TierColorPair>.toColors() =
    SentinelColors(mapValues { (_, p) -> TierColors(Color(p.content), Color(p.container)) })

private val LightTierColors = TierPalette.light.toColors()
private val DarkTierColors = TierPalette.dark.toColors()

val LocalSentinelColors = staticCompositionLocalOf { LightTierColors }

/** Access to design-system extras that Material's ColorScheme has no slot for (tier colours). */
object SentinelTheme {
    val colors: SentinelColors
        @Composable get() = LocalSentinelColors.current
}

/**
 * App theme. Dynamic colour is OFF by default: the tier colours carry meaning and the brand palette is
 * deliberate; users may opt in via settings (Android 12+). Tier colours are never dynamic.
 */
@Composable
fun SentinelTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val scheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> DarkColors
        else -> LightColors
    }
    CompositionLocalProvider(LocalSentinelColors provides if (darkTheme) DarkTierColors else LightTierColors) {
        MaterialTheme(
            colorScheme = scheme,
            typography = SentinelTypography,
            shapes = SentinelShapes,
            content = content,
        )
    }
}
