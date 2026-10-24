package ai.enthral.sentinel.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ai.enthral.sentinel.R
import ai.enthral.sentinel.domain.ConsentSource
import ai.enthral.sentinel.domain.SourceStatus
import ai.enthral.sentinel.domain.Tier
import ai.enthral.sentinel.ui.theme.SentinelTheme
import ai.enthral.sentinel.ui.theme.Spacing

fun ConsentSource.titleRes() = when (this) {
    ConsentSource.Notifications -> R.string.source_notifications
    ConsentSource.Calls -> R.string.source_calls
    ConsentSource.Check -> R.string.source_check
}

fun ConsentSource.summaryRes() = when (this) {
    ConsentSource.Notifications -> R.string.source_notifications_summary
    ConsentSource.Calls -> R.string.source_calls_summary
    ConsentSource.Check -> R.string.source_check_summary
}

fun ConsentSource.icon(): ImageVector = when (this) {
    ConsentSource.Notifications -> SentinelIcons.Message
    ConsentSource.Calls -> SentinelIcons.Phone
    ConsentSource.Check -> SentinelIcons.Check
}

/**
 * One consent source as a grouped-list row: icon tile, name, what it covers, its live status, a "What we read" link
 * (and "Fix" when Android permission is missing) and a switch. "Check this" is always on, so it has no switch.
 */
@Composable
fun SourceRow(
    source: ConsentSource,
    status: SourceStatus,
    onToggle: (Boolean) -> Unit,
    onInfo: () -> Unit,
    onFix: () -> Unit,
    modifier: Modifier = Modifier,
    fixLabel: Int = R.string.source_fix,
) {
    val p = SentinelTheme.palette
    val haptics = rememberHaptics()
    val title = stringResource(source.titleRes())
    val locked = source == ConsentSource.Check
    val statusText = stringResource(
        when (status) {
            SourceStatus.On -> if (locked) R.string.source_always_on else R.string.source_status_on
            SourceStatus.NeedsPermission -> R.string.source_status_needs_permission
            SourceStatus.Off -> R.string.source_status_off
        },
    )
    val caution = SentinelTheme.colors[Tier.Caution].content
    val (statusIcon, statusColor) = when (status) {
        SourceStatus.On -> SentinelIcons.Checkmark to p.accent
        SourceStatus.NeedsPermission -> SentinelIcons.Info to caution
        SourceStatus.Off -> null to p.secondaryLabel
    }
    Row(
        modifier.fillMaxWidth().padding(start = Spacing.x2, end = Spacing.x2, top = Spacing.x15, bottom = Spacing.x05),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(Spacing.x15),
    ) {
        Column(Modifier.padding(top = 1.dp)) { IconTile(source.icon()) }
        Column(Modifier.weight(1f)) {
            Text(title, style = SentinelTheme.type.body, color = p.label)
            Spacer(Modifier.height(2.dp))
            Text(stringResource(source.summaryRes()), style = SentinelTheme.type.subhead, color = p.secondaryLabel)
            // Status and links share one line; FlowRow wraps them under each other at large font sizes.
            @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
            androidx.compose.foundation.layout.FlowRow(
                horizontalArrangement = Arrangement.spacedBy(Spacing.x15),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                StatusText(statusText, statusIcon, statusColor)
                LinkButton(stringResource(R.string.source_what_we_read), onInfo)
                if (status == SourceStatus.NeedsPermission) LinkButton(stringResource(fixLabel), onFix)
            }
        }
        if (!locked) {
            Switch(
                checked = status != SourceStatus.Off,
                onCheckedChange = { haptics.toggle(it); onToggle(it) },
                colors = sentinelSwitchColors(),
                modifier = Modifier.semantics {
                    contentDescription = title
                    stateDescription = statusText
                },
            )
        }
    }
}

@Composable
private fun StatusText(text: String, icon: ImageVector?, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.x05)) {
        if (icon != null) Icon(icon, null, Modifier.size(14.dp), tint = color)
        Text(text, style = SentinelTheme.type.footnote.copy(fontWeight = FontWeight.Medium), color = color)
    }
}

@Composable
private fun LinkButton(text: String, onClick: () -> Unit) {
    PlainButton(text, onClick, contentPadding = PaddingValues(horizontal = 0.dp, vertical = Spacing.x1)) {
        Text(text, style = SentinelTheme.type.subhead.copy(fontWeight = FontWeight.SemiBold))
    }
}

@Composable
private fun Bullets(items: List<Int>, icon: ImageVector, tint: Color) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.x15)) {
        items.forEach {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.x15)) {
                Icon(icon, null, tint = tint, modifier = Modifier.padding(top = 2.dp).size(18.dp))
                Text(stringResource(it), style = SentinelTheme.type.callout, color = SentinelTheme.palette.label, modifier = Modifier.weight(1f))
            }
        }
    }
}

private fun ConsentSource.read() = when (this) {
    ConsentSource.Notifications -> listOf(R.string.consent_notif_read_1, R.string.consent_notif_read_2, R.string.consent_notif_read_3)
    ConsentSource.Calls -> listOf(R.string.consent_calls_read_1, R.string.consent_calls_read_2)
    ConsentSource.Check -> listOf(R.string.consent_check_read_1, R.string.consent_check_read_2)
}

private fun ConsentSource.never() = when (this) {
    ConsentSource.Notifications -> listOf(R.string.consent_notif_never_1, R.string.consent_notif_never_2, R.string.consent_notif_never_3)
    ConsentSource.Calls -> listOf(R.string.consent_calls_never_1, R.string.consent_calls_never_2, R.string.consent_calls_never_3)
    ConsentSource.Check -> listOf(R.string.consent_check_never_1, R.string.consent_check_never_2)
}

/** "What we read / what we never read" sheet; doubles as the rationale shown before any permission request. */
@Composable
fun ConsentSheet(
    source: ConsentSource,
    allowLabel: Int?,
    onAllow: () -> Unit,
    onDismiss: () -> Unit,
) {
    val p = SentinelTheme.palette
    SentinelSheet(onDismiss = onDismiss) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.x15)) {
            IconTile(source.icon())
            Text(stringResource(source.titleRes()), style = SentinelTheme.type.title2, modifier = Modifier.semantics { heading() })
        }
        Text(stringResource(R.string.consent_sheet_intro), style = SentinelTheme.type.callout, color = p.secondaryLabel)
        SheetHeading(stringResource(R.string.consent_we_read))
        Bullets(source.read(), SentinelIcons.Checkmark, p.accent)
        SheetHeading(stringResource(R.string.consent_we_never_read))
        Bullets(source.never(), SentinelIcons.EyeOff, p.secondaryLabel)
        val note = when (source) {
            ConsentSource.Calls -> R.string.consent_calls_permission_note
            ConsentSource.Notifications -> R.string.consent_notif_permission_note
            ConsentSource.Check -> null
        }
        if (note != null) {
            Text(stringResource(note), style = SentinelTheme.type.footnote, color = p.secondaryLabel, modifier = Modifier.padding(top = Spacing.x1))
        }
        Spacer(Modifier.height(Spacing.x1))
        if (allowLabel != null) {
            PrimaryButton(stringResource(allowLabel), onAllow, Modifier.fillMaxWidth())
            PlainButton(stringResource(R.string.consent_not_now), onDismiss, Modifier.fillMaxWidth(), color = p.label)
        } else {
            PrimaryButton(stringResource(R.string.action_got_it), onDismiss, Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun SheetHeading(text: String) {
    Text(
        text,
        style = SentinelTheme.type.headline,
        modifier = Modifier.padding(top = Spacing.x15).semantics { heading() },
    )
}
