package ai.enthral.sentinel.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
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
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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

/**
 * Home hero: answers "Am I protected?" at a glance. A large status line and a shield glyph that breathes slowly when
 * protected and morphs between states. The one gradient in the app is the soft wash behind it.
 * Meaning is carried by glyph + title + sentence; colour only reinforces.
 */
@Composable
fun StatusHero(
    status: ProtectionStatus,
    sourcesOn: String,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val p = SentinelTheme.palette
    val caution = SentinelTheme.colors[Tier.Caution]
    val (wash, tint) = when (status) {
        ProtectionStatus.Protected, ProtectionStatus.SetupIncomplete -> p.accentSoft to p.accent
        ProtectionStatus.Limited -> caution.container to caution.content
        ProtectionStatus.Paused, ProtectionStatus.Offline -> p.elevatedSecondary to p.secondaryLabel
    }
    val reduced = rememberReducedMotion()
    val shape = RoundedCornerShape(Radii.extraLarge)
    Column(
        modifier.fillMaxWidth().softShadow(shape).clip(shape)
            .background(p.elevated)
            .background(Brush.verticalGradient(0f to wash, 1f to wash.copy(alpha = 0f)))
            .padding(Spacing.x3),
    ) {
        AnimatedContent(
            targetState = status,
            transitionSpec = {
                if (reduced) fadeIn(tween(0)) togetherWith fadeOut(tween(0))
                else (fadeIn(Motion.standard()) + scaleIn(Motion.gentle(), initialScale = 0.7f)) togetherWith
                    (fadeOut(Motion.standard()) + scaleOut(Motion.standard(), targetScale = 1.15f))
            },
            label = "hero-glyph",
        ) { s -> HeroGlyph(s.style().icon, tint, breathing = s == ProtectionStatus.Protected && !reduced) }
        Spacer(Modifier.height(Spacing.x25))
        val s = status.style()
        Text(stringResource(s.title), style = SentinelTheme.type.title1, color = p.label, modifier = Modifier.semantics { heading() })
        Spacer(Modifier.height(Spacing.x05))
        Text(
            if (status == ProtectionStatus.Protected) stringResource(s.body, sourcesOn) else stringResource(s.body),
            style = SentinelTheme.type.callout,
            color = p.secondaryLabel,
        )
        if (s.action != null) {
            Spacer(Modifier.height(Spacing.x25))
            if (status == ProtectionStatus.SetupIncomplete) PrimaryButton(stringResource(s.action), onAction, Modifier.fillMaxWidth())
            else SecondaryButton(stringResource(s.action), onAction, Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun HeroGlyph(icon: ImageVector, tint: Color, breathing: Boolean) {
    val p = SentinelTheme.palette
    val scale = if (breathing) {
        val v by rememberInfiniteTransition(label = "breathe").animateFloat(
            1f, 1.035f, infiniteRepeatable(tween(2000, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "s",
        )
        v
    } else 1f
    Box(
        Modifier.size(64.dp).graphicsLayer { scaleX = scale; scaleY = scale }
            .softShadow(CircleShape).background(p.elevated, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(36.dp))
    }
}
