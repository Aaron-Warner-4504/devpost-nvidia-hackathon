package ai.enthral.sentinel.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.enthral.sentinel.ui.common.rememberReducedMotion
import ai.enthral.sentinel.ui.theme.Inter
import ai.enthral.sentinel.ui.theme.SentinelTheme

/*
 * Illustrations are drawn in code on a 120 x 120 design grid (scaled to the requested size), using only the accent,
 * its soft wash and the neutral layers, so they follow light/dark and stay tiny. All are decorative for TalkBack.
 */

private const val GRID = 120f
private const val SHIELD_PATH = "M60 14c12 7 24.5 11.5 37 13.5v31c0 24-15.5 44-37 50-21.5-6-37-26-37-50v-31C35.5 25.5 48 21 60 14z"

private fun path(d: String): Path = PathParser().parsePathString(d).toPath()

@Composable
private fun Grid(size: Dp, modifier: Modifier, draw: DrawScope.() -> Unit) {
    Canvas(modifier.size(size).clearAndSetSemantics { }) {
        val s = this.size.minDimension / GRID
        scale(s, pivot = Offset.Zero) { draw() }
    }
}

private fun DrawScope.line(d: Path, color: Color, width: Float) =
    drawPath(d, color, style = Stroke(width, cap = StrokeCap.Round, join = StrokeJoin.Round))

/** Shield on soft concentric halos. [breathing] gently pulses the outer halo (off with reduced motion). */
@Composable
fun ShieldIllustration(modifier: Modifier = Modifier, size: Dp = 168.dp, breathing: Boolean = true) {
    val p = SentinelTheme.palette
    val reduced = rememberReducedMotion()
    val shield = remember { path(SHIELD_PATH) }
    val check = remember { path("M45 61l10 10 21-22") }
    val halo = if (breathing && !reduced) {
        rememberInfiniteTransition(label = "halo").animateFloat(
            0.94f, 1.04f, infiniteRepeatable(tween(2200, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "s",
        ).value
    } else 1f
    Grid(size, modifier) {
        drawCircle(p.accentSoft.copy(alpha = 0.55f), radius = 58f * halo, center = Offset(60f, 60f))
        drawCircle(p.accentSoft, radius = 46f, center = Offset(60f, 60f))
        translate(0f, 0f) {
            scale(0.62f, pivot = Offset(60f, 62f)) {
                drawPath(shield, p.elevated)
                line(shield, p.accent, 4.5f)
                line(check, p.accent, 6f)
            }
        }
    }
}

/** Three message cards; the front one has its digits masked into dots, with a small lock. */
@Composable
fun MaskedMessagesIllustration(modifier: Modifier = Modifier, size: Dp = 168.dp) {
    val p = SentinelTheme.palette
    val lock = remember { path("M0 9h14v10H0z") }
    Grid(size, modifier) {
        drawCircle(p.accentSoft.copy(alpha = 0.55f), radius = 58f, center = Offset(60f, 60f))
        // back cards
        drawRoundRect(p.elevatedSecondary, Offset(30f, 22f), Size(66f, 30f), CornerRadius(10f))
        drawRoundRect(p.elevated.copy(alpha = 0.9f), Offset(24f, 34f), Size(72f, 34f), CornerRadius(11f))
        drawRoundRect(p.separator, Offset(32f, 44f), Size(36f, 4f), CornerRadius(2f))
        // front card
        drawRoundRect(p.elevated, Offset(16f, 52f), Size(84f, 44f), CornerRadius(12f))
        drawRoundRect(p.separator, Offset(16f, 52f), Size(84f, 44f), CornerRadius(12f), style = Stroke(1f))
        drawRoundRect(p.label.copy(alpha = 0.16f), Offset(26f, 62f), Size(44f, 5f), CornerRadius(2.5f))
        // masked digits pill
        drawRoundRect(p.accentSoft, Offset(26f, 74f), Size(46f, 13f), CornerRadius(6.5f))
        repeat(4) { i -> drawCircle(p.accent, 2.6f, Offset(34f + i * 10f, 80.5f)) }
        // lock badge
        drawCircle(p.accent, 13f, Offset(90f, 90f))
        translate(83f, 80f) {
            drawPath(lock, p.onAccent)
            drawArc(p.onAccent, 180f, 180f, false, Offset(2.5f, 3.5f), Size(9f, 11f), style = Stroke(2.2f))
        }
    }
}

/** Two people under one shield, joined by a dotted line: family protection. */
@Composable
fun FamilyIllustration(modifier: Modifier = Modifier, size: Dp = 168.dp) {
    val p = SentinelTheme.palette
    val shield = remember { path(SHIELD_PATH) }
    Grid(size, modifier) {
        drawCircle(p.accentSoft.copy(alpha = 0.55f), radius = 58f, center = Offset(60f, 60f))
        // left person (larger)
        drawCircle(p.elevated, 11f, Offset(40f, 56f))
        drawArc(p.elevated, 180f, 180f, true, Offset(22f, 70f), Size(36f, 34f))
        line(Path().apply { addOval(androidx.compose.ui.geometry.Rect(29f, 45f, 51f, 67f)) }, p.accent, 2.6f)
        drawArc(p.accent, 180f, 180f, false, Offset(22f, 72f), Size(36f, 32f), style = Stroke(2.6f, cap = StrokeCap.Round))
        // right person (smaller)
        drawCircle(p.elevated, 9f, Offset(82f, 62f))
        line(Path().apply { addOval(androidx.compose.ui.geometry.Rect(73f, 53f, 91f, 71f)) }, p.accent, 2.6f)
        drawArc(p.accent, 180f, 180f, false, Offset(67f, 76f), Size(30f, 26f), style = Stroke(2.6f, cap = StrokeCap.Round))
        // dotted link
        drawLine(p.accent.copy(alpha = 0.6f), Offset(52f, 60f), Offset(72f, 64f), 2.2f, StrokeCap.Round, PathEffect.dashPathEffect(floatArrayOf(0.1f, 6f)))
        // shield badge above
        translate(0f, 0f) {
            scale(0.26f, pivot = Offset(61f, 22f)) {
                drawPath(shield, p.accent)
            }
        }
        line(path("M56.6 31.6l3.2 3.2 5.6-6"), p.onAccent, 2.2f)
    }
}

/** Two overlapping speech bubbles with "A" and "अ": choose your language. */
@Composable
fun LanguageIllustration(modifier: Modifier = Modifier, size: Dp = 168.dp) {
    val p = SentinelTheme.palette
    val measurer = rememberTextMeasurer()
    val styleA = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 26.sp, color = p.accent)
    val styleB = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 26.sp, color = p.onAccent)
    Grid(size, modifier) {
        drawCircle(p.accentSoft.copy(alpha = 0.55f), radius = 58f, center = Offset(60f, 60f))
        drawRoundRect(p.elevated, Offset(18f, 30f), Size(52f, 44f), CornerRadius(14f))
        drawPath(Path().apply { moveTo(28f, 72f); lineTo(26f, 84f); lineTo(40f, 73f); close() }, p.elevated)
        drawRoundRect(p.accent, Offset(52f, 48f), Size(52f, 44f), CornerRadius(14f))
        drawPath(Path().apply { moveTo(92f, 90f); lineTo(95f, 101f); lineTo(81f, 91f); close() }, p.accent)
        // Inside the grid scale 1 px == 1 grid unit: size the glyphs in grid units, independent of font scale.
        val a = measurer.measure("A", styleA.copy(fontSize = 26f.toSp()))
        val b = measurer.measure("अ", styleB.copy(fontSize = 26f.toSp()))
        drawText(a, topLeft = Offset(44f - a.size.width / 2f, 52f - a.size.height / 2f))
        drawText(b, topLeft = Offset(78f - b.size.width / 2f, 70f - b.size.height / 2f))
    }
}
