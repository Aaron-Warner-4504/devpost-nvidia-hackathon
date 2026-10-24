package ai.enthral.sentinel.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import ai.enthral.sentinel.R
import ai.enthral.sentinel.domain.ConsentSource
import ai.enthral.sentinel.domain.SourceStatus
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

/** One consent source: status, a switch (or "always on"), a "what we read" link and a fix-it action. */
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
    val title = stringResource(source.titleRes())
    val locked = source == ConsentSource.Check
    val statusText = stringResource(
        when (status) {
            SourceStatus.On -> if (locked) R.string.source_always_on else R.string.source_status_on
            SourceStatus.NeedsPermission -> R.string.source_status_needs_permission
            SourceStatus.Off -> R.string.source_status_off
        },
    )
    Row(
        modifier.fillMaxWidth().heightIn(min = 72.dp).padding(vertical = Spacing.x1),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.x2),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.x05)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(
                stringResource(source.summaryRes()),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.x05)) {
                if (status == SourceStatus.On) {
                    Icon(Icons.Filled.CheckCircle, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                }
                Text(
                    statusText,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (status == SourceStatus.NeedsPermission) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.x3)) {
                TextButton(onClick = onInfo, modifier = Modifier.heightIn(min = Spacing.minTouchTarget), contentPadding = PaddingValues(0.dp)) {
                    Text(stringResource(R.string.source_what_we_read))
                }
                if (status == SourceStatus.NeedsPermission) {
                    TextButton(onClick = onFix, modifier = Modifier.heightIn(min = Spacing.minTouchTarget), contentPadding = PaddingValues(0.dp)) {
                        Text(stringResource(fixLabel))
                    }
                }
            }
        }
        if (!locked) {
            Switch(
                checked = status != SourceStatus.Off,
                onCheckedChange = onToggle,
                modifier = Modifier.semantics {
                    contentDescription = title
                    stateDescription = statusText
                },
            )
        }
    }
}

@Composable
private fun Bullets(items: List<Int>) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.x1)) {
        items.forEach {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.x1)) {
                Text("•", style = MaterialTheme.typography.bodyMedium)
                Text(stringResource(it), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
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
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConsentSheet(
    source: ConsentSource,
    allowLabel: Int?,
    onAllow: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.verticalScrollColumn().padding(horizontal = Spacing.x3).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(Spacing.x1),
        ) {
            Text(stringResource(source.titleRes()), style = MaterialTheme.typography.headlineSmall)
            Text(
                stringResource(R.string.consent_sheet_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SectionHeader(stringResource(R.string.consent_we_read))
            Bullets(source.read())
            SectionHeader(stringResource(R.string.consent_we_never_read))
            Bullets(source.never())
            if (source == ConsentSource.Calls) {
                Text(
                    stringResource(R.string.consent_calls_permission_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = Spacing.x1),
                )
            }
            if (source == ConsentSource.Notifications) {
                Text(
                    stringResource(R.string.consent_notif_permission_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = Spacing.x1),
                )
            }
            Column(Modifier.padding(vertical = Spacing.x2), verticalArrangement = Arrangement.spacedBy(Spacing.x1)) {
                if (allowLabel != null) {
                    PrimaryButton(stringResource(allowLabel), onAllow, Modifier.fillMaxWidth())
                    SecondaryButton(stringResource(R.string.consent_not_now), onDismiss, Modifier.fillMaxWidth())
                } else {
                    PrimaryButton(stringResource(R.string.action_got_it), onDismiss, Modifier.fillMaxWidth())
                }
            }
        }
    }
}

@Composable
private fun Modifier.verticalScrollColumn(): Modifier = this.verticalScroll(rememberScrollState())
