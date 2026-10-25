package ai.enthral.sentinel.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ripple
import androidx.compose.material3.RippleConfiguration
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
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
val LocalPalette = staticCompositionLocalOf { Palettes.light.toCompose() }
val LocalType = staticCompositionLocalOf { LatinType }

/** Design-system access beyond Material's slots: tier colours, the layered neutral palette and the type scale. */
object SentinelTheme {
    val colors: SentinelColors
        @Composable get() = LocalSentinelColors.current
    val palette: SentinelPalette
        @Composable get() = LocalPalette.current
    val type: SentinelType
        @Composable get() = LocalType.current
}

/**
 * App theme. No Material dynamic colour: tier colours carry meaning and the accent is deliberate (docs/design.md).
 * [trueBlack] switches the dark canvas to pure black for OLED screens.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SentinelTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    trueBlack: Boolean = false,
    reduceTransparency: Boolean = false,
    content: @Composable () -> Unit,
) {
    val glass = rememberGlassSettings(reduceTransparency)
    val clock = rememberAtmosphereClock(glass.motion)
    val palette = when {
        !darkTheme -> Palettes.light
        trueBlack -> Palettes.trueBlack
        else -> Palettes.dark
    }
    val hindi = LocalConfiguration.current.locales[0]?.language == "hi"
    val type = if (hindi) DevanagariType else LatinType
    val scheme = remember(palette) { palette.toColorScheme() }
    val composePalette = remember(palette) { palette.toCompose() }
    val typography = remember(type) { type.toMaterial() }
    // Quieter ripple: a soft press wash in the label colour instead of Material's stronger state layer.
    val ripple = remember(composePalette) {
        RippleConfiguration(color = composePalette.label, rippleAlpha = androidx.compose.material.ripple.RippleAlpha(0.06f, 0.06f, 0.04f, 0.08f))
    }
    CompositionLocalProvider(
        LocalSentinelColors provides if (darkTheme) DarkTierColors else LightTierColors,
        LocalPalette provides composePalette,
        LocalType provides type,
        LocalGlass provides glass,
        LocalAtmosphereClock provides clock,
    ) {
        MaterialTheme(colorScheme = scheme, typography = typography, shapes = SentinelShapes) {
            CompositionLocalProvider(
                LocalRippleConfiguration provides ripple,
                LocalContentColor provides composePalette.label,
                content = content,
            )
        }
    }
}

/** Seconds since the atmosphere started drifting; shared so every screen's background is in the same position. */
val LocalAtmosphereClock = staticCompositionLocalOf<androidx.compose.runtime.State<Float>> { androidx.compose.runtime.mutableFloatStateOf(0f) }

/**
 * Ticks ~15 times a second while the UI is visible and motion is allowed: the drift is glacial, so 15 fps is smooth
 * and keeps the bars' blur from re-rendering every vsync. Uses the infinite-animation frame clock so Compose UI
 * tests never wait for it.
 */
@Composable
private fun rememberAtmosphereClock(motion: Boolean): androidx.compose.runtime.State<Float> {
    val t = remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
    if (motion) {
        androidx.compose.runtime.LaunchedEffect(Unit) {
            var start = -1L
            var last = 0L
            while (true) {
                androidx.compose.animation.core.withInfiniteAnimationFrameMillis { now ->
                    if (start < 0) start = now - (t.floatValue * 1000).toLong()
                    if (now - last >= 66) {
                        last = now
                        t.floatValue = (now - start) / 1000f
                    }
                }
            }
        }
    }
    return t
}
