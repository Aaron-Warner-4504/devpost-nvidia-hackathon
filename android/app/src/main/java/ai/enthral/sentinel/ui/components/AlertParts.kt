package ai.enthral.sentinel.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import ai.enthral.sentinel.R
import ai.enthral.sentinel.domain.Alert
import ai.enthral.sentinel.domain.FeedbackLabel
import ai.enthral.sentinel.domain.Signals
import ai.enthral.sentinel.domain.Tier
import ai.enthral.sentinel.domain.WhyItem
import ai.enthral.sentinel.ui.common.RelativeTime
import ai.enthral.sentinel.ui.common.relativeTime
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

/** Vertical "why we flagged this" timeline. The current event is highlighted (and appended if not in the list). */
@Composable
fun WhyTimeline(alert: Alert, now: Instant, modifier: Modifier = Modifier) {
    val colors = SentinelTheme.colors[alert.tier]
    val items = alert.why
    val hasCurrent = items.any { it.eventId == alert.eventId }
    val rows = items.map { Triple(it.timestamp, it.text, it.eventId == alert.eventId) } +
        if (hasCurrent) emptyList() else listOf(Triple(alert.createdAt, stringResource(R.string.why_this_message), true))
    Column(modifier.fillMaxWidth()) {
        rows.forEachIndexed { index, (time, text, current) ->
            val last = index == rows.lastIndex
            val rel = relativeTimeText(time, now)
            val abs = formatTimestamp(time)
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).semantics(mergeDescendants = true) {}) {
                Column(
                    Modifier.width(28.dp).fillMaxHeight(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        Modifier.padding(top = 6.dp).size(if (current) 16.dp else 12.dp)
                            .then(
                                if (current) Modifier.background(colors.content, CircleShape)
                                else Modifier.border(2.dp, MaterialTheme.colorScheme.primary, CircleShape),
                            ),
                    )
                    if (!last) {
                        Box(
                            Modifier.weight(1f).width(2.dp)
                                .background(MaterialTheme.colorScheme.outlineVariant),
                        )
                    }
                }
                Surface(
                    Modifier.fillMaxWidth().padding(bottom = if (last) 0.dp else Spacing.x1),
                    color = if (current) colors.container else androidx.compose.ui.graphics.Color.Transparent,
                    contentColor = if (current) colors.content else MaterialTheme.colorScheme.onSurface,
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Column(Modifier.padding(horizontal = Spacing.x1, vertical = Spacing.x05)) {
                        Text(
                            "$rel · $abs",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (current) colors.content else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(text, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SignalChips(signals: List<String>, modifier: Modifier = Modifier) {
    FlowRow(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Spacing.x1),
        verticalArrangement = Arrangement.spacedBy(Spacing.x1),
    ) {
        signals.distinct().forEach { name ->
            val label = Signals.labelRes(name)?.let { stringResource(it) } ?: Signals.humanise(name)
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = MaterialTheme.colorScheme.onSurface,
                shape = MaterialTheme.shapes.small,
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = Spacing.x1),
                )
            }
        }
    }
}


@Composable
fun NoteCard(text: String, modifier: Modifier = Modifier, iconRes: Int? = null) {
    Card(
        modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Row(Modifier.padding(Spacing.x2), horizontalArrangement = Arrangement.spacedBy(Spacing.x2), verticalAlignment = Alignment.Top) {
            if (iconRes != null) {
                Icon(painterResource(iconRes), null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
            } else {
                Icon(Icons.Filled.Lock, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
            }
            Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
fun FeedbackBar(selected: FeedbackLabel?, onSelect: (FeedbackLabel) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.x1)) {
        Text(stringResource(R.string.feedback_title), style = MaterialTheme.typography.titleSmall)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.x1)) {
            FeedbackButton(FeedbackLabel.Scam, selected == FeedbackLabel.Scam, R.string.feedback_scam, onSelect, Modifier.weight(1f))
            FeedbackButton(FeedbackLabel.Safe, selected == FeedbackLabel.Safe, R.string.feedback_safe, onSelect, Modifier.weight(1f))
        }
        if (selected != null) {
            Text(
                stringResource(R.string.feedback_thanks),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun FeedbackButton(
    label: FeedbackLabel,
    selected: Boolean,
    text: Int,
    onSelect: (FeedbackLabel) -> Unit,
    modifier: Modifier,
) {
    val content: @Composable () -> Unit = {
        if (selected) {
            Icon(Icons.Filled.Check, null, Modifier.size(18.dp))
            androidx.compose.foundation.layout.Spacer(Modifier.size(Spacing.x1))
        }
        Text(stringResource(text), style = MaterialTheme.typography.labelLarge, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
    val m = modifier.heightIn(min = Spacing.minTouchTarget)
    if (selected) {
        androidx.compose.material3.Button(onClick = { onSelect(label) }, modifier = m, shape = MaterialTheme.shapes.medium) { content() }
    } else {
        androidx.compose.material3.OutlinedButton(onClick = { onSelect(label) }, modifier = m, shape = MaterialTheme.shapes.medium) { content() }
    }
}
