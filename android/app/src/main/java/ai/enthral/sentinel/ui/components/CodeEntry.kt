package ai.enthral.sentinel.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.enthral.sentinel.R
import ai.enthral.sentinel.domain.Tier
import ai.enthral.sentinel.ui.common.rememberReducedMotion
import ai.enthral.sentinel.ui.theme.Radii
import ai.enthral.sentinel.ui.theme.SentinelTheme
import ai.enthral.sentinel.ui.theme.Spacing

/**
 * Six-cell code field backed by ONE text field: typing auto-advances, backspace steps back and pasting a whole
 * code (even with spaces/dashes) fills every cell. The caller normalises the text.
 */
@Composable
fun CodeEntry(
    code: String,
    onCodeChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    isError: Boolean = false,
    length: Int = 6,
) {
    var focused by remember { mutableStateOf(false) }
    val p = SentinelTheme.palette
    val critical = SentinelTheme.colors[Tier.Critical].content
    val description = stringResource(R.string.family_code_field, code.length, length)
    val cellText = SentinelTheme.type.title1.copy(fontFeatureSettings = "tnum", textAlign = TextAlign.Center)
    BasicTextField(
        value = code,
        onValueChange = onCodeChange,
        enabled = enabled,
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.Characters,
            imeAction = ImeAction.Next,
            autoCorrectEnabled = false,
        ),
        cursorBrush = SolidColor(Color.Transparent),
        textStyle = cellText.copy(color = Color.Transparent),
        modifier = modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused }
            .semantics { contentDescription = description },
        decorationBox = {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.x1)) {
                repeat(length) { i ->
                    if (i == length / 2) Spacer(Modifier.width(Spacing.x1))
                    val active = focused && i == code.length.coerceAtMost(length - 1)
                    val shape = RoundedCornerShape(Radii.medium)
                    Box(
                        Modifier.weight(1f).heightIn(min = 60.dp)
                            .background(p.elevated, shape)
                            .then(
                                when {
                                    isError -> Modifier.border(1.5.dp, critical, shape)
                                    active -> Modifier.border(1.5.dp, p.accent, shape)
                                    else -> Modifier
                                },
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(code.getOrNull(i)?.toString() ?: "", style = cellText, color = p.label)
                    }
                }
            }
        },
    )
}

/**
 * Invite code as large tabular characters in fixed cells (3 + 3), spelled out for TalkBack. The code is already
 * 40 sp, so its growth is capped at 1.3x font scale (like the tab bar) to keep all six cells on one line.
 */
@Composable
fun CodeDisplay(code: String, modifier: Modifier = Modifier, dimmed: Boolean = false) {
    val p = SentinelTheme.palette
    val density = androidx.compose.ui.platform.LocalDensity.current
    val scale = minOf(density.fontScale, 1.3f)
    val base = SentinelTheme.type.code.fontSize.value
    val style = SentinelTheme.type.code.copy(
        fontSize = (base * scale / density.fontScale).sp,
        lineHeight = (base * 1.2f * scale / density.fontScale).sp,
        letterSpacing = 0.sp,
    )
    val cell = (base * scale * 0.8f).dp
    val spoken = code.toCharArray().joinToString(" ")
    Row(
        modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = spoken },
        horizontalArrangement = Arrangement.Center,
    ) {
        code.forEachIndexed { i, ch ->
            if (i == code.length / 2 && code.length > 3) Spacer(Modifier.width(cell * 0.35f))
            Box(Modifier.width(cell), contentAlignment = Alignment.Center) {
                Text(
                    ch.toString(),
                    style = style,
                    color = if (dimmed) p.tertiaryLabel else p.label,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
    }
}

/** Thin ring that empties as the code's lifetime runs out. */
@Composable
fun CountdownRing(fraction: Float, modifier: Modifier = Modifier, size: Dp = 22.dp) {
    val p = SentinelTheme.palette
    val reduced = rememberReducedMotion()
    val f by animateFloatAsState(fraction.coerceIn(0f, 1f), if (reduced) tween(0) else tween(900), label = "ring")
    Canvas(modifier.size(size)) {
        val w = 2.5.dp.toPx()
        drawCircle(p.separator, style = Stroke(w), radius = this.size.minDimension / 2 - w / 2)
        drawArc(
            p.accent, startAngle = -90f, sweepAngle = 360f * f, useCenter = false,
            topLeft = androidx.compose.ui.geometry.Offset(w / 2, w / 2),
            size = androidx.compose.ui.geometry.Size(this.size.width - w, this.size.height - w),
            style = Stroke(w, cap = StrokeCap.Round),
        )
    }
}
