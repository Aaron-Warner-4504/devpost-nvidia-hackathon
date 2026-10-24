package ai.enthral.sentinel.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import ai.enthral.sentinel.R
import ai.enthral.sentinel.domain.Tier
import ai.enthral.sentinel.ui.common.rememberReducedMotion
import ai.enthral.sentinel.ui.theme.SentinelTheme
import ai.enthral.sentinel.ui.theme.Spacing
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * Tier shape (circle / diamond / triangle / octagon) on a soft disc of the tier colour. Critical pulses once when it
 * first appears (a single expanding ring), never loops. Decorative unless [announce] is set.
 */
@Composable
fun TierGlyph(tier: Tier, modifier: Modifier = Modifier, size: Dp = 40.dp, announce: Boolean = false, pulse: Boolean = tier == Tier.Critical) {
    val c = SentinelTheme.colors[tier]
    val reduced = rememberReducedMotion()
    val ring = remember { Animatable(if (pulse && !reduced) 0f else 1f) }
    val pop = remember { Animatable(if (pulse && !reduced) 0.86f else 1f) }
    LaunchedEffect(Unit) {
        if (pulse && !reduced) {
            coroutineScope {
                launch { pop.animateTo(1f, androidx.compose.animation.core.spring(dampingRatio = 0.45f, stiffness = 300f)) }
                launch { ring.animateTo(1f, tween(900, easing = FastOutSlowInEasing)) }
            }
        }
    }
    val label = stringResource(tier.presentation().label)
    val description = stringResource(R.string.tier_a11y, label)
    Box(
        modifier.size(size)
            .then(if (announce) Modifier.clearAndSetSemantics { contentDescription = description } else Modifier.clearAndSetSemantics { })
            .drawBehind {
                val t = ring.value
                if (t < 1f) {
                    drawCircle(
                        color = c.content.copy(alpha = 0.35f * (1f - t)),
                        radius = this.size.minDimension / 2f * (1f + 0.45f * t),
                        style = Stroke(width = 2.dp.toPx()),
                    )
                }
            }
            .graphicsLayer { scaleX = pop.value; scaleY = pop.value }
            .background(c.container, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(tier.presentation().icon), null, tint = c.content, modifier = Modifier.size(size * 0.56f))
    }
}

/** Glyph + text label on the tier wash; announced as a single "Risk level: X" item. */
@Composable
fun TierBadge(tier: Tier, modifier: Modifier = Modifier) {
    val colors = SentinelTheme.colors[tier]
    val p = tier.presentation()
    val label = stringResource(p.label)
    val description = stringResource(R.string.tier_a11y, label)
    Surface(
        modifier = modifier.clearAndSetSemantics { contentDescription = description },
        color = colors.container,
        contentColor = colors.content,
        shape = CircleShape,
    ) {
        Row(
            Modifier.padding(start = Spacing.x1, end = Spacing.x15, top = 3.dp, bottom = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Icon(painterResource(p.icon), contentDescription = null, modifier = Modifier.size(15.dp))
            Text(label, style = SentinelTheme.type.footnote.copy(fontWeight = FontWeight.SemiBold))
        }
    }
}

/**
 * Today's quiet summary: one column per tier (count in large tabular figures, glyph + label under it).
 * Zero counts are shown dimmed so the row keeps its rhythm.
 */
@Composable
fun TierCountRow(counts: Map<Tier, Int>, modifier: Modifier = Modifier) {
    val p = SentinelTheme.palette
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        listOf(Tier.Critical, Tier.High, Tier.Caution).forEach { tier ->
            val n = counts[tier] ?: 0
            val c = SentinelTheme.colors[tier]
            val label = stringResource(tier.presentation().label)
            val text = stringResource(R.string.tier_count, n, label)
            Column(
                Modifier.weight(1f).clearAndSetSemantics { contentDescription = text },
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    n.toString(),
                    style = SentinelTheme.type.title1.copy(fontFeatureSettings = "tnum"),
                    color = if (n > 0) p.label else p.tertiaryLabel,
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    Icon(painterResource(tier.presentation().icon), null, tint = if (n > 0) c.content else p.tertiaryLabel, modifier = Modifier.size(14.dp))
                    Text(label, style = SentinelTheme.type.footnote, color = p.secondaryLabel)
                }
            }
        }
    }
}
