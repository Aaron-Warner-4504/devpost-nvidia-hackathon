package ai.enthral.sentinel.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import kotlin.math.roundToInt
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ai.enthral.sentinel.LocalDemoEnabled
import ai.enthral.sentinel.R
import ai.enthral.sentinel.domain.ConsentSource
import ai.enthral.sentinel.domain.ProtectionStatus
import ai.enthral.sentinel.domain.SourceStatus
import ai.enthral.sentinel.domain.Tier
import ai.enthral.sentinel.ui.common.CaptureState
import ai.enthral.sentinel.ui.common.graphViewModel
import ai.enthral.sentinel.ui.common.openNotificationAccessSettings
import ai.enthral.sentinel.ui.components.CaptureStatusCard
import ai.enthral.sentinel.ui.components.AlertCard
import ai.enthral.sentinel.ui.components.ErrorState
import ai.enthral.sentinel.ui.components.GroupedHeader
import ai.enthral.sentinel.ui.components.IconTile
import ai.enthral.sentinel.ui.components.LargeTitleList
import ai.enthral.sentinel.ui.components.LoadingSkeleton
import ai.enthral.sentinel.ui.components.NoteCard
import ai.enthral.sentinel.ui.components.SentinelIcons
import ai.enthral.sentinel.ui.components.StatusHero
import ai.enthral.sentinel.ui.components.TierGlyph
import ai.enthral.sentinel.ui.components.glass
import ai.enthral.sentinel.ui.components.presentation
import ai.enthral.sentinel.ui.components.apiErrorMessage
import ai.enthral.sentinel.ui.components.pressScale
import ai.enthral.sentinel.ui.components.shimmer
import ai.enthral.sentinel.ui.components.titleRes
import ai.enthral.sentinel.ui.theme.Radii
import ai.enthral.sentinel.ui.theme.SentinelTheme
import ai.enthral.sentinel.ui.theme.Spacing

@Composable
fun HomeScreen(
    onOpenAlert: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenCheck: () -> Unit,
    onOpenDemo: () -> Unit,
) {
    val vm = graphViewModel { HomeViewModel.create(it) }
    val state by vm.state.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.onResume() }
    HomeContent(
        state = state,
        demoEnabled = LocalDemoEnabled.current,
        onRefresh = { vm.refresh() },
        onOpenAlert = onOpenAlert,
        onOpenSettings = onOpenSettings,
        onOpenCheck = onOpenCheck,
        onOpenDemo = onOpenDemo,
    )
}

private val CardPadding = Modifier.padding(horizontal = Spacing.inset)

@Composable
fun HomeContent(
    state: HomeUiState,
    demoEnabled: Boolean,
    onRefresh: () -> Unit,
    onOpenAlert: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenCheck: () -> Unit,
    onOpenDemo: () -> Unit,
) {
    val sourcesOn = ConsentSource.entries
        .filter { it != ConsentSource.Check && state.sources.status(it) == SourceStatus.On }
        .map { stringResource(it.titleRes()) }
        .joinToString(", ")
    LargeTitleList(
        title = stringResource(R.string.app_name),
        refreshing = state.refreshing,
        onRefresh = onRefresh,
    ) {
        item(key = "hero") {
            // A critical/high-risk alert from today turns "Protected" into "Needs your attention" (opens that alert).
            val attention = state.attention
            StatusHero(
                status = state.protection,
                sourcesOn = sourcesOn,
                onAction = if (state.protection == ProtectionStatus.Offline) onRefresh else onOpenSettings,
                modifier = CardPadding.animateItem(),
                attention = attention?.tier,
                onOpenAttention = { attention?.let { onOpenAlert(it.id) } },
            )
        }
        // The hero already says "You are offline"; the note is only for when it shows another state (e.g. Paused).
        if (state.offline && state.phase == Phase.Ready && state.protection != ProtectionStatus.Offline) {
            item(key = "offline") {
                NoteCard(stringResource(R.string.home_offline_note), Modifier.padding(top = Spacing.x1).animateItem(), icon = SentinelIcons.CloudOff)
            }
        }

        item(key = "capture") {
            val context = androidx.compose.ui.platform.LocalContext.current
            CaptureStatusCard(
                state = state.capture,
                numbers = state.captureNumbers,
                now = minuteTick(),
                onFix = {
                    if (state.capture == CaptureState.NeedsAccess) context.openNotificationAccessSettings() else onOpenSettings()
                },
                modifier = CardPadding.padding(top = Spacing.x15).animateItem(),
            )
        }

        // Today's summary only ever shows real data: never claim "nothing was flagged" when nothing could be loaded.
        if (state.phase != Phase.Error) {
            item(key = "today-h") { HomeHeader(stringResource(R.string.home_today)) }
            item(key = "today") {
                if (state.phase == Phase.Loading) {
                    Box(CardPadding.fillMaxWidth().heightIn(min = 88.dp).shimmer(RoundedCornerShape(Radii.large)))
                } else {
                    TodayCard(state.todayCounts, CardPadding)
                }
            }
        }

        item(key = "latest-h") { HomeHeader(stringResource(R.string.home_latest_alert)) }
        when (state.phase) {
            Phase.Loading -> item(key = "latest-loading") { LoadingSkeleton(CardPadding, rows = 1) }
            Phase.Error -> item(key = "latest-error") {
                // The offline hero already offers "Try again": keep this a quiet line instead of a second error block.
                if (state.protection == ProtectionStatus.Offline) {
                    NoteCard(apiErrorMessage(state.failure!!), icon = SentinelIcons.CloudOff)
                } else {
                    ErrorState(apiErrorMessage(state.failure!!), onRefresh)
                }
            }
            Phase.Ready -> {
                val latest = state.latest
                if (latest == null) {
                    item(key = "latest-empty") {
                        HomeEmpty()
                    }
                } else {
                    item(key = latest.id) {
                        AlertCard(latest, onClick = { onOpenAlert(latest.id) }, modifier = CardPadding.animateItem())
                    }
                }
            }
        }

        item(key = "actions-h") { HomeHeader(stringResource(R.string.home_quick_actions)) }
        item(key = "actions") {
            Row(CardPadding.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.x15)) {
                QuickAction(SentinelIcons.Check, stringResource(R.string.home_action_check), onOpenCheck, Modifier.weight(1f))
                if (demoEnabled) QuickAction(SentinelIcons.Sparkles, stringResource(R.string.home_action_demo), onOpenDemo, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun HomeHeader(text: String) = GroupedHeader(text, Modifier.padding(horizontal = Spacing.inset, vertical = Spacing.x05))

/** Today as three glass tiles (count-up numbers), or one reassuring sentence when nothing happened. */
@Composable
private fun TodayCard(counts: Map<Tier, Int>, modifier: Modifier) {
    val p = SentinelTheme.palette
    val any = counts.values.any { it > 0 }
    if (!any) {
        Row(
            modifier.fillMaxWidth().glass(RoundedCornerShape(Radii.large)).padding(Spacing.x25),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.x15),
        ) {
            IconTile(SentinelIcons.ShieldCheck)
            Text(stringResource(R.string.home_today_none), style = SentinelTheme.type.callout, color = p.label)
        }
        return
    }
    Row(modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(Spacing.x15)) {
        listOf(Tier.Critical, Tier.High, Tier.Caution).forEachIndexed { i, tier ->
            TodayTile(tier, counts[tier] ?: 0, i, Modifier.weight(1f).fillMaxHeight())
        }
    }
}

@Composable
private fun TodayTile(tier: Tier, n: Int, index: Int, modifier: Modifier) {
    val p = SentinelTheme.palette
    val reduced = ai.enthral.sentinel.ui.common.rememberReducedMotion()
    val shown = remember { androidx.compose.animation.core.Animatable(if (reduced) n.toFloat() else 0f) }
    LaunchedEffect(n) {
        if (reduced) shown.snapTo(n.toFloat())
        else {
            kotlinx.coroutines.delay(120L + index * 60L)
            shown.animateTo(n.toFloat(), androidx.compose.animation.core.tween(700, easing = androidx.compose.animation.core.FastOutSlowInEasing))
        }
    }
    val label = stringResource(tier.presentation().label)
    val description = stringResource(R.string.tier_count, n, label)
    Column(
        modifier.glass(RoundedCornerShape(Radii.large)).clearAndSetSemantics { contentDescription = description }
            .padding(horizontal = Spacing.x15, vertical = Spacing.x2),
        verticalArrangement = Arrangement.spacedBy(Spacing.x1),
    ) {
        TierGlyph(tier, size = 30.dp, pulse = false)
        Text(
            shown.value.roundToInt().toString(),
            style = SentinelTheme.type.title1.copy(fontFeatureSettings = "tnum"),
            color = if (n > 0) p.label else p.tertiaryLabel,
        )
        Text(label, style = SentinelTheme.type.footnote, color = p.secondaryLabel)
    }
}

@Composable
private fun HomeEmpty() {
    val p = SentinelTheme.palette
    Column(
        CardPadding.fillMaxWidth().glass(RoundedCornerShape(Radii.large)).padding(Spacing.x25),
        verticalArrangement = Arrangement.spacedBy(Spacing.x05),
    ) {
        Text(stringResource(R.string.home_empty_title), style = SentinelTheme.type.headline, color = p.label)
        Text(stringResource(R.string.home_empty_body), style = SentinelTheme.type.subhead, color = p.secondaryLabel)
    }
}

@Composable
private fun QuickAction(icon: ImageVector, label: String, onClick: () -> Unit, modifier: Modifier) {
    val p = SentinelTheme.palette
    val interaction = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(Radii.large)
    Column(
        modifier.heightIn(min = 104.dp).pressScale(interaction).glass(shape)
            .clickable(interactionSource = interaction, indication = ripple(), role = Role.Button, onClick = onClick)
            .padding(Spacing.x2),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Box(Modifier.size(40.dp).background(p.accentSoft, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = p.accent, modifier = Modifier.size(24.dp))
        }
        Text(label, style = SentinelTheme.type.headline, color = p.label, modifier = Modifier.padding(top = Spacing.x15))
    }
}

/** The current time, refreshed every 30 s so "last event 3 min ago" stays true while the screen is open. */
@Composable
private fun minuteTick(): java.time.Instant {
    val now = androidx.compose.runtime.produceState(java.time.Instant.now()) {
        while (true) {
            kotlinx.coroutines.delay(30_000)
            value = java.time.Instant.now()
        }
    }
    return now.value
}
