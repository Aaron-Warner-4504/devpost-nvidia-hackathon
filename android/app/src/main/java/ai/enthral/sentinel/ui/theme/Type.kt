package ai.enthral.sentinel.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import ai.enthral.sentinel.R
import kotlin.math.exp

/**
 * Inter (variable, subset to Latin, OFL; see third_party/inter). Devanagari glyphs are not in the subset, so Android
 * falls back to the system Noto Sans Devanagari for Hindi, which matches Inter's proportions closely.
 */
@OptIn(ExperimentalTextApi::class)
private fun inter(weight: FontWeight) = Font(
    R.font.inter_variable,
    weight = weight,
    variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)),
)

val Inter = FontFamily(
    inter(FontWeight.Normal),
    inter(FontWeight.Medium),
    inter(FontWeight.SemiBold),
    inter(FontWeight.Bold),
)

/** Inter's recommended tracking curve (rsms.me/inter/dynmetrics), in em. Tighter at display sizes. */
private fun tracking(sizeSp: Float): TextUnit = (-0.0223f + 0.185f * exp(-0.1745f * sizeSp)).em

private val NoTrim = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None)

/** The Sentinel type scale (docs/design.md). Screens use these names, not Material's slots. */
@Immutable
data class SentinelType(
    val largeTitle: TextStyle,
    val title1: TextStyle,
    val title2: TextStyle,
    val title3: TextStyle,
    val headline: TextStyle,
    val body: TextStyle,
    val bodyEmphasis: TextStyle,
    val callout: TextStyle,
    val subhead: TextStyle,
    val footnote: TextStyle,
    val caption: TextStyle,
    /** Button labels. */
    val button: TextStyle,
    /** Invite codes: large, tabular, widely tracked. */
    val code: TextStyle,
)

/**
 * @param lineScale Devanagari needs more vertical room for matras and conjuncts: Hindi uses ~1.12x line heights.
 */
fun sentinelType(lineScale: Float = 1f): SentinelType {
    fun style(size: Float, line: Float, weight: FontWeight, tabular: Boolean = false) = TextStyle(
        fontFamily = Inter,
        fontWeight = weight,
        fontSize = size.sp,
        lineHeight = (line * lineScale).sp,
        letterSpacing = tracking(size),
        lineHeightStyle = NoTrim,
        fontFeatureSettings = if (tabular) "tnum, calt" else "calt",
    )
    return SentinelType(
        largeTitle = style(34f, 41f, FontWeight.SemiBold),
        title1 = style(28f, 34f, FontWeight.SemiBold),
        title2 = style(22f, 28f, FontWeight.SemiBold),
        title3 = style(20f, 25f, FontWeight.SemiBold),
        headline = style(17f, 22f, FontWeight.SemiBold),
        body = style(17f, 24f, FontWeight.Normal),
        bodyEmphasis = style(17f, 24f, FontWeight.Medium),
        callout = style(16f, 22f, FontWeight.Normal),
        subhead = style(15f, 20f, FontWeight.Normal),
        footnote = style(13f, 18f, FontWeight.Normal, tabular = true),
        caption = style(12f, 16f, FontWeight.Medium, tabular = true),
        button = style(17f, 22f, FontWeight.SemiBold),
        code = style(40f, 48f, FontWeight.SemiBold, tabular = true).copy(letterSpacing = 0.18.em),
    )
}

val LatinType = sentinelType()
val DevanagariType = sentinelType(lineScale = 1.12f)

/** Material slots mapped onto the Sentinel scale so stock components (fields, snackbars, sheets) match. */
fun SentinelType.toMaterial() = Typography(
    displayLarge = largeTitle,
    displayMedium = largeTitle,
    displaySmall = largeTitle,
    headlineLarge = largeTitle,
    headlineMedium = title1,
    headlineSmall = title2,
    titleLarge = title3,
    titleMedium = headline,
    titleSmall = subhead.copy(fontWeight = FontWeight.SemiBold),
    bodyLarge = body,
    bodyMedium = callout,
    bodySmall = footnote,
    labelLarge = subhead.copy(fontWeight = FontWeight.SemiBold),
    labelMedium = footnote.copy(fontWeight = FontWeight.Medium),
    labelSmall = caption,
)

/** Kept for previews/tests that still ask for the Material typography directly. */
val SentinelTypography = LatinType.toMaterial()
