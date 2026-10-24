package ai.enthral.sentinel.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import ai.enthral.sentinel.ui.common.rememberReducedMotion
import ai.enthral.sentinel.ui.theme.Motion
import ai.enthral.sentinel.ui.theme.SentinelTheme

/** Named haptics so screens say what they mean (docs/design.md, Motion). */
class Haptics(private val h: HapticFeedback) {
    /** Light tick: tab, segment, chip selection. */
    fun tick() = h.performHapticFeedback(HapticFeedbackType.SegmentTick)
    fun toggle(on: Boolean) = h.performHapticFeedback(if (on) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff)
    /** Success: feedback sent, code copied, link created. */
    fun confirm() = h.performHapticFeedback(HapticFeedbackType.Confirm)
    /** Warning: a critical alert opens. */
    fun reject() = h.performHapticFeedback(HapticFeedbackType.Reject)
}

@Composable
fun rememberHaptics(): Haptics {
    val h = LocalHapticFeedback.current
    return remember(h) { Haptics(h) }
}

/** Buttons and cards dip to 97% while pressed (spring); static when animations are off. */
@Composable
fun Modifier.pressScale(interaction: InteractionSource, pressed: Float = 0.97f): Modifier {
    val reduced = rememberReducedMotion()
    val isPressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (isPressed && !reduced) pressed else 1f, Motion.gentle(), label = "press")
    return graphicsLayer { scaleX = scale; scaleY = scale }
}

/** The one soft, diffuse shadow (light mode only; in dark mode surfaces lift by being lighter). */
@Composable
fun Modifier.softShadow(shape: Shape): Modifier {
    val p = SentinelTheme.palette
    return if (p.isDark) this else shadow(14.dp, shape, clip = false, ambientColor = p.shadow, spotColor = p.shadow)
}

/** Skeleton fill with a slow highlight sweep (plain fill when animations are off). */
@Composable
fun Modifier.shimmer(shape: Shape): Modifier {
    val p = SentinelTheme.palette
    val base = p.elevatedSecondary
    val highlight = if (p.isDark) Color.White.copy(alpha = 0.06f) else Color.White.copy(alpha = 0.85f)
    if (rememberReducedMotion()) return background(base, shape)
    val x by rememberInfiniteTransition(label = "shimmer").animateFloat(
        initialValue = -1f,
        targetValue = 2f,
        animationSpec = infiniteRepeatable(tween(1200, easing = LinearEasing), RepeatMode.Restart),
        label = "x",
    )
    return background(base, shape).background(
        Brush.linearGradient(
            colors = listOf(Color.Transparent, highlight, Color.Transparent),
            start = androidx.compose.ui.geometry.Offset(x * 600f, 0f),
            end = androidx.compose.ui.geometry.Offset(x * 600f + 400f, 200f),
        ),
        shape,
    )
}
