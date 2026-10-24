package ai.enthral.sentinel.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle

/**
 * Material 3 type scale with roomier line heights (>= 1.4x) so Devanagari matras and conjuncts are not clipped,
 * and no line-height trimming. Font family stays the platform default: Android falls back to Noto Sans
 * Devanagari for Hindi, so no font asset ships in the APK.
 */
private fun TextStyle.roomy(ratio: Float = 1.45f, weight: FontWeight? = null) = copy(
    lineHeight = fontSize * ratio,
    fontWeight = weight ?: fontWeight,
    lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
)

private val base = Typography()

val SentinelTypography = Typography(
    displayLarge = base.displayLarge.roomy(1.3f),
    displayMedium = base.displayMedium.roomy(1.3f),
    displaySmall = base.displaySmall.roomy(1.3f),
    headlineLarge = base.headlineLarge.roomy(1.35f, FontWeight.SemiBold),
    headlineMedium = base.headlineMedium.roomy(1.35f, FontWeight.SemiBold),
    headlineSmall = base.headlineSmall.roomy(1.4f, FontWeight.SemiBold),
    titleLarge = base.titleLarge.roomy(1.4f, FontWeight.SemiBold),
    titleMedium = base.titleMedium.roomy(1.45f, FontWeight.SemiBold),
    titleSmall = base.titleSmall.roomy(1.45f, FontWeight.SemiBold),
    bodyLarge = base.bodyLarge.roomy(1.5f),
    bodyMedium = base.bodyMedium.roomy(1.5f),
    bodySmall = base.bodySmall.roomy(1.5f),
    labelLarge = base.labelLarge.roomy(1.45f, FontWeight.Medium),
    labelMedium = base.labelMedium.roomy(1.45f, FontWeight.Medium),
    labelSmall = base.labelSmall.roomy(1.45f, FontWeight.Medium),
)
