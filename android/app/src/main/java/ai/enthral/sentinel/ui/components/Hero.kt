package ai.enthral.sentinel.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import ai.enthral.sentinel.R
import ai.enthral.sentinel.domain.ProtectionStatus
import ai.enthral.sentinel.domain.Tier
import ai.enthral.sentinel.ui.common.rememberReducedMotion
import ai.enthral.sentinel.ui.theme.LocalGlass
import ai.enthral.sentinel.ui.theme.Motion
import ai.enthral.sentinel.ui.theme.Radii
import ai.enthral.sentinel.ui.theme.SentinelTheme
import ai.enthral.sentinel.ui.theme.Spacing

private data class HeroStyle(val icon: ImageVector, val title: Int, val body: Int, val action: Int?)

private fun ProtectionStatus.style() = when (this) {
    ProtectionStatus.Protected -> HeroStyle(SentinelIcons.ShieldCheck, R.string.status_protected_title, R.string.status_protected_body, null)
    ProtectionStatus.SetupIncomplete -> HeroStyle(SentinelIcons.ShieldPlus, R.string.status_setup_title, R.string.status_setup_body, R.string.status_setup_action)
    ProtectionStatus.Limited -> HeroStyle(SentinelIcons.ShieldAlert, R.string.status_limited_title, R.string.status_limited_body, R.string.status_limited_action)
    ProtectionStatus.Paused -> HeroStyle(SentinelIcons.ShieldPause, R.string.status_paused_title, R.string.status_paused_body, R.string.status_paused_action)
    ProtectionStatus.Offline -> HeroStyle(SentinelIcons.CloudOff, R.string.status_offline_title, R.string.status_offline_body, R.string.action_retry)
}

/** What the hero shows: a protection state, or (only while Protected) attention for today's serious alert. */
private sealed interface HeroState {
    data class Status(val status: ProtectionStatus) : HeroState
    data class Attention(val tier: Tier) : HeroState
}

/**
 * Home hero: answers "Am I protected?" at a glance. A glass card with a soft inner glow, and a glass medallion
 * whose shield breathes (and a slow orbit sweeps) while protected. If a critical or high-risk alert arrived today
 * the Protected state morphs into "Needs your attention" in that tier's hue, with one action to open it.
 * Meaning is carried by glyph + title + sentence; colour only reinforces.
 */
@Composable
fun StatusHero(
    status: ProtectionStatus,
    sourcesOn: String,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
    attention: Tier? = null,
    onOpenAttention: () -> Unit = {},
) {
    val p = SentinelTheme.palette
    val caution = SentinelTheme.colors[Tier.Caution]
    val state: HeroState = if (attention != null && status == ProtectionStatus.Protected) HeroState.Attention(attention) else HeroState.Status(status)
    val tint = when (state) {
        is HeroState.Attention -> SentinelTheme.colors[state.tier].content
        is HeroState.Status -> when (state.status) {
            ProtectionStatus.Protected, ProtectionStatus.SetupIncomplete -> p.accent
            ProtectionStatus.Limited -> caution.content
            ProtectionStatus.Paused, ProtectionStatus.Offline -> p.secondaryLabel
        }
    }
    val reduced = rememberReducedMotion()
    val glassOn = LocalGlass.current.glass
    val shape = RoundedCornerShape(Radii.extraLarge)
    val glowAlpha = if (p.isDark) 0.34f else 0.22f
    Column(
        modifier.fillMaxWidth()
            .glass(shape, glow = (state as? HeroState.Attention)?.let { tint.copy(alpha = 0.5f) })
            // The one rich gradient on Home: a soft glow of the state colour behind the medallion.
            .drawBehind {
                val c = Offset(56.dp.toPx() + 24.dp.toPx(), 56.dp.toPx() + 8.dp.toPx())
                val r = size.width * 0.75f
                drawCircle(Brush.radialGradient(listOf(tint.copy(alpha = if (glassOn) glowAlpha else glowAlpha * 0.6f), tint.copy(alpha = 0f)), c, r), r, c)
            }
            .padding(Spacing.x3),
    ) {
        AnimatedContent(
            targetState = state,
            transitionSpec = {
                if (reduced) fadeIn(tween(0)) togetherWith fadeOut(tween(0))
                else (fadeIn(Motion.standard()) + scaleIn(Motion.gentle(), initialScale = 0.7f)) togetherWith
                    (fadeOut(Motion.standard()) + scaleOut(Motion.standard(), targetScale = 1.15f))
            },
            label = "hero-glyph",
        ) { s ->
            val icon = when (s) {
                is HeroState.Attention -> SentinelIcons.ShieldAlert
                is HeroState.Status -> s.status.style().icon
            }
            val live = !reduced && (s is HeroState.Attention || (s as HeroState.Status).status == ProtectionStatus.Protected)
            Medallion(icon, tint, live = live)
        }
        Spacer(Modifier.height(Spacing.x25))
        when (state) {
            is HeroState.Attention -> {
                Text(stringResource(R.string.home_attention_title), style = SentinelTheme.type.title1, color = p.label, modifier = Modifier.semantics { heading() })
                Spacer(Modifier.height(Spacing.x05))
                Text(
                    stringResource(R.string.home_attention_body, stringResource(state.tier.presentation().label).lowercase()),
                    style = SentinelTheme.type.callout,
                    color = p.secondaryLabel,
                )
                Spacer(Modifier.height(Spacing.x25))
                PrimaryButton(stringResource(R.string.home_attention_action), onOpenAttention, Modifier.fillMaxWidth())
            }
            is HeroState.Status -> {
                val s = state.status.style()
                Text(stringResource(s.title), style = SentinelTheme.type.title1, color = p.label, modifier = Modifier.semantics { heading() })
                Spacer(Modifier.height(Spacing.x05))
                Text(
                    if (state.status == ProtectionStatus.Protected) stringResource(s.body, sourcesOn) else stringResource(s.body),
                    style = SentinelTheme.type.callout,
                    color = p.secondaryLabel,
                )
                if (s.action != null) {
                    Spacer(Modifier.height(Spacing.x25))
                    if (state.status == ProtectionStatus.SetupIncomplete) PrimaryButton(stringResource(s.action), onAction, Modifier.fillMaxWidth())
                    else SecondaryButton(stringResource(s.action), onAction, Modifier.fillMaxWidth())
                }
            }
        }
    }
}

/**
 * Glass medallion: a frosted disc with a light edge, the state glyph inside, and (when [live]) a breathing shield
 * and a slow orbit arc that sweeps around it like a quiet radar.
 */
@Composable
private fun Medallion(icon: ImageVector, tint: Color, live: Boolean) {
    val p = SentinelTheme.palette
    val transition = rememberInfiniteTransition(label = "medallion")
    val scale = if (live) transition.animateFloat(1f, 1.04f, infiniteRepeatable(tween(2200, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "breathe").value else 1f
    val sweep = if (live) transition.animateFloat(0f, 360f, infiniteRepeatable(tween(9000, easing = LinearEasing)), label = "orbit") else null
    val disc = if (p.isDark) Color.White.copy(alpha = 0.08f) else Color.White.copy(alpha = 0.85f)
    Box(Modifier.size(84.dp), contentAlignment = Alignment.Center) {
        Box(
            Modifier.size(84.dp).drawBehind {
                val w = 1.5.dp.toPx()
                drawCircle(tint.copy(alpha = 0.22f), radius = size.minDimension / 2 - w, style = Stroke(w))
                if (sweep != null) {
                    drawArc(
                        Brush.sweepGradient(listOf(tint.copy(alpha = 0f), tint.copy(alpha = 0.9f)), center),
                        startAngle = sweep.value, sweepAngle = 80f, useCenter = false,
                        topLeft = Offset(w, w), size = Size(size.width - 2 * w, size.height - 2 * w),
                        style = Stroke(w * 1.6f, cap = StrokeCap.Round),
                    )
                }
            },
        )
        Box(
            Modifier.size(64.dp).graphicsLayer { scaleX = scale; scaleY = scale }
                .softShadow(CircleShape).background(disc, CircleShape)
                .border(1.dp, Brush.linearGradient(listOf(Color.White.copy(alpha = if (p.isDark) 0.3f else 1f), Color.White.copy(alpha = 0f))), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, tint = tint, modifier = Modifier.size(34.dp))
        }
    }
}
