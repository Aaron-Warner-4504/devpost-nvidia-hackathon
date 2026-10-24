package ai.enthral.sentinel.ui.theme

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp

/** 8-dp spacing grid (4 dp for tight pairs). Use these instead of raw dp literals. */
object Spacing {
    val x05 = 4.dp
    val x1 = 8.dp
    val x15 = 12.dp
    val x2 = 16.dp
    val x25 = 20.dp
    val x3 = 24.dp
    val x4 = 32.dp
    val x5 = 40.dp
    val x6 = 48.dp

    /** Minimum interactive size (accessibility). */
    val minTouchTarget = 48.dp

    /** Minimum height of a grouped-list row (one line / with subtitle). */
    val rowHeight = 52.dp
    val rowHeightTwoLine = 60.dp

    /** Horizontal screen gutter for free text. */
    val gutter = 20.dp

    /** Horizontal inset of grouped lists and cards. */
    val inset = 16.dp

    /** Hairline separators. */
    val hairline = 0.5.dp
}

/** Corner radii (docs/design.md). */
object Radii {
    val small = 10.dp
    val medium = 14.dp
    val large = 20.dp
    val extraLarge = 28.dp
}

val SentinelShapes = Shapes(
    extraSmall = RoundedCornerShape(Radii.small),
    small = RoundedCornerShape(Radii.small),
    medium = RoundedCornerShape(Radii.medium),
    large = RoundedCornerShape(Radii.large),
    extraLarge = RoundedCornerShape(Radii.extraLarge),
)

/** Motion tokens. Everything is skipped when the system "remove animations" setting is on. */
object Motion {
    /** Default UI spring: quick, no visible overshoot. */
    fun <T> standard(): FiniteAnimationSpec<T> = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 500f)

    /** Slightly playful spring for glyph morphs and press feedback. */
    fun <T> gentle(): FiniteAnimationSpec<T> = spring(dampingRatio = 0.75f, stiffness = Spring.StiffnessMediumLow)

    val offset: FiniteAnimationSpec<IntOffset> = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 500f)
}
