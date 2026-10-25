package ai.enthral.sentinel.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ai.enthral.sentinel.R
import ai.enthral.sentinel.domain.Alert
import ai.enthral.sentinel.domain.FeedbackLabel
import ai.enthral.sentinel.domain.Signals
import ai.enthral.sentinel.ui.common.RelativeTime
import ai.enthral.sentinel.ui.common.relativeTime
import ai.enthral.sentinel.ui.theme.Radii
import ai.enthral.sentinel.ui.theme.SentinelTheme
import ai.enthral.sentinel.ui.theme.Spacing
import java.time.Instant

@Composable
fun relativeTimeText(then: Instant, now: Instant): String = when (val r = relativeTime(then, now)) {
    RelativeTime.JustNow -> stringResource(R.string.time_just_now)
    is RelativeTime.MinutesAgo -> pluralStringResource(R.plurals.time_minutes_ago, r.n, r.n)
    is RelativeTime.HoursAgo -> pluralStringResource(R.plurals.time_hours_ago, r.n, r.n)
    is RelativeTime.DaysAgo -> pluralStringResource(R.plurals.time_days_ago, r.n, r.n)
}

@Composable
private fun signalLabel(name: String): String = Signals.labelRes(name)?.let { stringResource(it) } ?: Signals.humanise(name)

/** Home's latest alert: one lifted card with a soft shadow. Glyph + tier word + time, headline, what to do. */
@Composable
fun AlertCard(alert: Alert, onClick: () -> Unit, modifier: Modifier = Modifier, now: Instant = remember { Instant.now() }) {
    val p = SentinelTheme.palette
    val tier = SentinelTheme.colors[alert.tier]
    val shape = RoundedCornerShape(Radii.large)
    val interaction = remember { MutableInteractionSource() }
    Column(
        modifier.fillMaxWidth().pressScale(interaction, 0.98f)
            .glass(shape) // the luminous edge is reserved for hero surfaces, never stacked
            .clickable(interactionSource = interaction, indication = androidx.compose.material3.ripple(), role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) {}
            .padding(Spacing.x25),
        verticalArrangement = Arrangement.spacedBy(Spacing.x15),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.x15)) {
            TierGlyph(alert.tier, size = 36.dp, pulse = false, announce = false)
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(alert.tier.presentation().label),
                    style = SentinelTheme.type.footnote.copy(fontWeight = FontWeight.SemiBold),
                    color = tier.content,
                )
                Text(relativeTimeText(alert.createdAt, now), style = SentinelTheme.type.footnote, color = p.secondaryLabel)
            }
            Icon(SentinelIcons.ChevronRight, null, tint = p.tertiaryLabel, modifier = Modifier.size(18.dp))
        }
        Text(alert.headline, style = SentinelTheme.type.headline, color = p.label)
        Text(
            stringResource(R.string.alert_next_step, alert.nextStep),
            style = SentinelTheme.type.subhead,
            color = p.secondaryLabel,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Alerts list row: tier glyph, tier word + time, headline, one quiet line of "why" (plain-language signals). */
@Composable
fun AlertRow(alert: Alert, onClick: () -> Unit, modifier: Modifier = Modifier, selected: Boolean = false) {
    val p = SentinelTheme.palette
    val tier = SentinelTheme.colors[alert.tier]
    val why = alert.signals.distinct().take(2).map { signalLabel(it) }.joinToString(" · ").ifEmpty { alert.nextStep }
    Row(
        modifier.fillMaxWidth().heightIn(min = 72.dp)
            .then(if (selected) Modifier.background(p.accentSoft) else Modifier)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) {}
            .padding(horizontal = Spacing.x2, vertical = Spacing.x15),
        horizontalArrangement = Arrangement.spacedBy(Spacing.x15),
        verticalAlignment = Alignment.Top,
    ) {
        TierGlyph(alert.tier, size = 32.dp, pulse = false)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(alert.tier.presentation().label),
                    style = SentinelTheme.type.footnote.copy(fontWeight = FontWeight.SemiBold),
                    color = tier.content,
                    modifier = Modifier.weight(1f),
                )
                Text(formatTime(alert.createdAt), style = SentinelTheme.type.footnote, color = p.secondaryLabel)
            }
            // Never truncate the verdict: at large font sizes it simply wraps.
            Text(alert.headline, style = SentinelTheme.type.headline, color = p.label)
            Text(why, style = SentinelTheme.type.subhead, color = p.secondaryLabel, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * The product's differentiator: "why we flagged this" as a vertical timeline. Earlier events are quiet; the current
 * event sits on a soft tier wash with a filled, haloed node. Each event is one TalkBack item.
 */
@Composable
fun WhyTimeline(alert: Alert, now: Instant, modifier: Modifier = Modifier) {
    val p = SentinelTheme.palette
    val tier = SentinelTheme.colors[alert.tier]
    val items = alert.why
    val hasCurrent = items.any { it.eventId == alert.eventId }
    val rows = items.map { Triple(it.timestamp, it.text, it.eventId == alert.eventId) } +
        if (hasCurrent) emptyList() else listOf(Triple(alert.createdAt, stringResource(R.string.why_this_message), true))
    // The story draws itself: the connector fills in the tier hue from the first event to this one (~1 s),
    // then the current event's node glows. Static when animations are off.
    val reduced = ai.enthral.sentinel.ui.common.rememberReducedMotion()
    val reveal = remember { androidx.compose.animation.core.Animatable(if (reduced) 1f else 0f) }
    LaunchedEffect(alert.id) {
        if (!reduced) reveal.animateTo(1f, androidx.compose.animation.core.tween(500 + 250 * rows.size, easing = androidx.compose.animation.core.FastOutSlowInEasing))
    }
    val n = (rows.size - 1).coerceAtLeast(1)
    fun segment(i: Int) = (reveal.value * n - i).coerceIn(0f, 1f)
    val ink = tier.content.copy(alpha = 0.55f)
    Column(modifier.fillMaxWidth()) {
        rows.forEachIndexed { index, (time, text, current) ->
            val first = index == 0
            val last = index == rows.lastIndex
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).semantics(mergeDescendants = true) {}) {
                // Rail: connector above, node, connector below.
                Column(Modifier.width(28.dp).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        Modifier.width(2.dp).height(if (current) 7.dp else 11.dp)
                            .background(if (first) androidx.compose.ui.graphics.Color.Transparent else if (index > 0 && segment(index - 1) >= 1f) ink else p.separator),
                    )
                    if (current) {
                        val glow = if (reduced) 1f else ((reveal.value - 0.85f) / 0.15f).coerceIn(0f, 1f)
                        Box(
                            Modifier.size(20.dp)
                                .drawBehind {
                                    val r = size.minDimension * 1.3f
                                    drawCircle(androidx.compose.ui.graphics.Brush.radialGradient(listOf(tier.content.copy(alpha = 0.45f * glow), tier.content.copy(alpha = 0f)), center, r), r)
                                }
                                .background(tier.container, CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            Box(Modifier.size(10.dp).background(tier.content, CircleShape))
                        }
                    } else {
                        Box(Modifier.size(12.dp).background(p.elevated, CircleShape).padding(2.5.dp).background(p.tertiaryLabel, CircleShape))
                    }
                    if (!last) {
                        val progress = segment(index)
                        Box(
                            Modifier.width(2.dp).weight(1f).background(p.separator).drawBehind {
                                drawLine(ink, androidx.compose.ui.geometry.Offset(size.width / 2, 0f), androidx.compose.ui.geometry.Offset(size.width / 2, size.height * progress), size.width)
                            },
                        )
                    }
                }
                Spacer(Modifier.width(Spacing.x1))
                Column(
                    Modifier.weight(1f).padding(bottom = if (last) 0.dp else Spacing.x1)
                        .then(
                            if (current) Modifier.clip(RoundedCornerShape(Radii.medium)).background(tier.container)
                                .padding(horizontal = Spacing.x15, vertical = Spacing.x1)
                            else Modifier.padding(horizontal = Spacing.x05, vertical = Spacing.x1),
                        ),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        "${relativeTimeText(time, now)} · ${formatTimestamp(time)}",
                        style = SentinelTheme.type.footnote.copy(fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal),
                        color = if (current) tier.content else p.secondaryLabel,
                    )
                    Text(
                        text,
                        style = if (current) SentinelTheme.type.bodyEmphasis else SentinelTheme.type.body,
                        color = p.label,
                    )
                }
            }
        }
    }
}

/** Signals as quiet chips in plain language. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SignalChips(signals: List<String>, modifier: Modifier = Modifier) {
    val p = SentinelTheme.palette
    FlowRow(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Spacing.x1),
        verticalArrangement = Arrangement.spacedBy(Spacing.x1),
    ) {
        signals.distinct().forEach { name ->
            Text(
                signalLabel(name),
                style = SentinelTheme.type.subhead,
                color = p.label,
                modifier = Modifier.glassFlat(RoundedCornerShape(Radii.small))
                    .padding(horizontal = Spacing.x15, vertical = Spacing.x1),
            )
        }
    }
}

/** "Was this right?" with two equal-weight answers; the chosen one fills and shows a check. */
@Composable
fun FeedbackBar(selected: FeedbackLabel?, onSelect: (FeedbackLabel) -> Unit, modifier: Modifier = Modifier) {
    val haptics = rememberHaptics()
    val pick: (FeedbackLabel) -> Unit = { haptics.confirm(); onSelect(it) }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.x15), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            stringResource(R.string.feedback_title),
            style = SentinelTheme.type.headline,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { heading() },
        )
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(Spacing.x15)) {
            FeedbackButton(FeedbackLabel.Scam, selected, R.string.feedback_scam, pick, Modifier.weight(1f).fillMaxHeight())
            FeedbackButton(FeedbackLabel.Safe, selected, R.string.feedback_safe, pick, Modifier.weight(1f).fillMaxHeight())
        }
        if (selected != null) {
            Text(
                stringResource(R.string.feedback_thanks),
                style = SentinelTheme.type.footnote,
                color = SentinelTheme.palette.secondaryLabel,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun FeedbackButton(
    label: FeedbackLabel,
    selected: FeedbackLabel?,
    text: Int,
    onSelect: (FeedbackLabel) -> Unit,
    modifier: Modifier,
) {
    if (selected == label) {
        PrimaryButton(stringResource(text), { onSelect(label) }, modifier, icon = SentinelIcons.Checkmark)
    } else {
        SecondaryButton(stringResource(text), { onSelect(label) }, modifier)
    }
}
