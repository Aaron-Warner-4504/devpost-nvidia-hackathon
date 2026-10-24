package ai.enthral.sentinel.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/** 8-dp spacing grid (4 dp for tight pairs). Use these instead of raw dp literals. */
object Spacing {
    val x05 = 4.dp
    val x1 = 8.dp
    val x2 = 16.dp
    val x3 = 24.dp
    val x4 = 32.dp
    val x6 = 48.dp

    /** Minimum interactive size (accessibility). */
    val minTouchTarget = 48.dp

    /** Horizontal screen gutter. */
    val gutter = 16.dp
}

val SentinelShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)
