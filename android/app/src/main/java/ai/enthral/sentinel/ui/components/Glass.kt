package ai.enthral.sentinel.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import ai.enthral.sentinel.ui.theme.LocalAtmosphereClock
import ai.enthral.sentinel.ui.theme.LocalGlass
import ai.enthral.sentinel.ui.theme.SentinelTheme
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import kotlin.math.cos
import kotlin.math.sin

/**
 * One Haze state for the whole window: every screen's scrolling content (with its atmosphere) is a source, the top
 * bars and the floating bottom bar are effects. Null when glass is off, so nothing is captured at all.
 */
val LocalHaze = staticCompositionLocalOf<HazeState?> { null }

/** Blob anchors in fractions of the canvas, and drift speeds (rad/s): slow enough to be felt, not watched. */
private val Anchors = listOf(Offset(0.12f, 0.06f), Offset(0.92f, 0.18f), Offset(0.25f, 0.62f))
private val Speeds = listOf(0.045f, 0.037f, 0.031f)

/**
 * The living background: an opaque base with three soft radial blobs in the brand hues, drifting on the shared
 * atmosphere clock (static when motion is off). With glass off it is the plain grouped background.
 * Drawn per screen (so push/pop transitions stay opaque) but always in the same position.
 */
@Composable
fun Modifier.atmosphere(): Modifier {
    val glass = LocalGlass.current
    val p = SentinelTheme.palette
    if (!glass.glass) return background(p.groupedBackground)
    val clock = LocalAtmosphereClock.current
    val blobs = p.atmosphereBlobs
    val base = p.atmosphereBase
    // Drawn in WINDOW coordinates: every pane (sidebar, list, detail) shows its slice of one continuous aurora.
    val root = androidx.compose.ui.platform.LocalView.current.rootView
    val origin = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(Offset.Zero) }
    return onGloballyPositioned { origin.value = it.positionInWindow() }.drawBehind {
        drawRect(base)
        val t = clock.value // read in draw: drifting only re-draws, never recomposes
        val w = if (root.width > 0) root.width.toFloat() else size.width
        val h = if (root.height > 0) root.height.toFloat() else size.height
        val o = origin.value
        blobs.forEachIndexed { i, c ->
            val a = Anchors[i]
            val phase = t * Speeds[i] + i * 2.1f
            val center = Offset(w * (a.x + 0.10f * sin(phase)), h * (a.y + 0.06f * cos(phase * 0.8f))) - o
            val r = minOf(w, h * 0.75f) * (0.85f + 0.08f * sin(phase * 0.6f))
            drawCircle(Brush.radialGradient(listOf(c, c.copy(alpha = 0f)), center, r), r, center)
        }
    }
}

/**
 * Content over the atmosphere. The atmosphere is a SIBLING node behind the content: its clock ticks then only
 * re-record the gradients, never the content (a drawBehind on the container re-records everything inside it).
 * The blur source, if any, goes inside the content, never around the atmosphere.
 */
@Composable
fun AtmosphereBox(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) =
    Box(modifier.fillMaxSize()) {
        Box(Modifier.matchParentSize().atmosphere())
        content()
    }

enum class GlassTone { Card, Sheet }

/**
 * A glass card: soft shadow, translucent tint and a 1-px specular edge (light at the top-left, fading). Optional
 * [glow] (a tier hue) adds a luminous edge and a coloured diffuse shadow for a critical hero.
 * With glass off this is the opaque pass-1 surface, keeping the glow as a solid edge.
 */
@Composable
fun Modifier.glass(shape: Shape, glow: Color? = null, tone: GlassTone = GlassTone.Card): Modifier {
    val p = SentinelTheme.palette
    val on = LocalGlass.current.glass
    val fill = when {
        !on -> p.elevated
        tone == GlassTone.Sheet -> p.glassSheet
        else -> p.glassCard
    }
    val shadowed = when {
        glow != null -> outerHalo(shape, glow, 22.dp)
        p.isDark -> this
        else -> outerHalo(shape, p.shadow, 16.dp, offsetY = 4.dp)
    }
    val edge = when {
        glow != null -> Brush.linearGradient(listOf(glow.copy(alpha = 0.9f), glow.copy(alpha = 0.25f), glow.copy(alpha = 0.6f)))
        on -> Brush.linearGradient(
            0f to p.glassEdge,
            0.45f to p.glassEdge.copy(alpha = 0f),
            1f to p.glassEdge.copy(alpha = p.glassEdge.alpha * 0.35f),
        )
        else -> null
    }
    // Vibrancy: the tint carries a soft light sheen across its top half, composited into ONE gradient fill
    // (one blend per pixel instead of two: glass is fill-rate bound on weak GPUs).
    val base = if (on) {
        val top = p.glassSheen.compositeOverTint(fill)
        shadowed.clip(shape).background(Brush.verticalGradient(0f to top, 0.5f to fill, 1f to fill), shape)
    } else {
        shadowed.clip(shape).background(fill, shape)
    }
    return if (edge != null) base.border(if (glow != null) 1.5.dp else 1.dp, edge, shape) else base
}

/**
 * Navigation glass (top bar, floating bottom bar, rail): real backdrop blur of whatever scrolls beneath when
 * available, always under a tint heavy enough for text (PaletteContrastTest); otherwise a near-opaque tint.
 */
@Composable
fun Modifier.glassBar(shape: Shape? = null, alpha: Float = 1f): Modifier {
    val p = SentinelTheme.palette
    val g = LocalGlass.current
    val haze = LocalHaze.current
    val clipped = if (shape != null) clip(shape) else this
    if (!g.glass) return clipped.background(p.elevated.copy(alpha = alpha))
    return if (g.blur && haze != null) {
        clipped.hazeEffect(
            haze,
            HazeStyle(
                backgroundColor = p.atmosphereBase,
                tint = HazeTint(p.glassBar),
                blurRadius = 24.dp,
                noiseFactor = 0f,
            ),
        ) {
            this.alpha = alpha
            // Blur a 1/3-scale copy: a 24-dp blur hides the lower resolution and it is ~9x fewer pixels.
            inputScale = dev.chrisbanes.haze.HazeInputScale.Fixed(0.33f)
        }
    } else {
        clipped.background(p.glassBar.copy(alpha = (p.glassBar.alpha + 0.14f).coerceAtMost(1f) * alpha))
    }
}

/**
 * Marks content as what the bars blur. No-op when glass is off. Keep the atmosphere OUTSIDE the source: inside
 * Haze's recorded layer its draw-phase clock read is not re-observed and it would stop drifting.
 */
@Composable
fun Modifier.glassSource(): Modifier {
    val haze = LocalHaze.current
    return if (haze != null && LocalGlass.current.blur) hazeSource(haze) else this
}

/** Fill for surfaces inside lists and controls (rows, chips, fields): translucent glass, or opaque when off. */
@Composable
fun glassFill(): Color = if (LocalGlass.current.glass) SentinelTheme.palette.glassCard else SentinelTheme.palette.elevated

/** Flat glass: tint only, no shadow or edge, so stacked list rows never show seams. */
@Composable
fun Modifier.glassFlat(shape: Shape): Modifier = clip(shape).background(glassFill(), shape)

/** Height the floating bottom bar occupies over content (0 when there is none), so lists can scroll clear of it. */
val LocalBottomBarInset = staticCompositionLocalOf { 0.dp }

/**
 * A soft halo drawn only OUTSIDE [shape] (BlurMaskFilter.OUTER), so it never shows through translucent glass the way
 * a platform elevation shadow does. Used for the diffuse card shadow and the critical glow. API 28+ (where blur mask
 * filters are hardware accelerated); nothing below.
 */
fun Modifier.outerHalo(shape: Shape, color: Color, radius: androidx.compose.ui.unit.Dp, offsetY: androidx.compose.ui.unit.Dp = 0.dp): Modifier =
    if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.P) this else drawBehind {
        val outline = shape.createOutline(size, layoutDirection, this)
        val path = androidx.compose.ui.graphics.Path().apply { addOutline(outline) }
        path.translate(Offset(0f, offsetY.toPx()))
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color.copy(alpha = 1f).toArgbInt()
            this.alpha = (color.alpha * 255).toInt()
            maskFilter = android.graphics.BlurMaskFilter(radius.toPx(), android.graphics.BlurMaskFilter.Blur.OUTER)
        }
        drawContext.canvas.nativeCanvas.drawPath(path.asAndroidPath(), paint)
    }

private fun Color.toArgbInt(): Int = toArgb()

/** [this] (a translucent sheen) composited over a translucent [tint], as one colour with the tint's coverage. */
private fun Color.compositeOverTint(tint: Color): Color {
    val a = alpha
    return Color(
        red = red * a + tint.red * (1 - a),
        green = green * a + tint.green * (1 - a),
        blue = blue * a + tint.blue * (1 - a),
        alpha = tint.alpha,
    )
}
