package ai.enthral.sentinel.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.heading
import ai.enthral.sentinel.R
import ai.enthral.sentinel.domain.Tier
import ai.enthral.sentinel.ui.common.CaptureNumbers
import ai.enthral.sentinel.ui.common.CaptureState
import ai.enthral.sentinel.ui.theme.Radii
import ai.enthral.sentinel.ui.theme.SentinelTheme
import ai.enthral.sentinel.ui.theme.Spacing
import java.time.Instant

/** One line of truth about notification capture: what it watches and when it last worked, or what to fix. */
@Composable
fun CaptureStatusCard(
    state: CaptureState,
    numbers: CaptureNumbers,
    now: Instant,
    onFix: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val p = SentinelTheme.palette
    val title: String
    val body: String
    val icon = when (state) {
        CaptureState.Active -> SentinelIcons.ShieldCheck
        CaptureState.Paused -> SentinelIcons.ShieldPause
        CaptureState.NeedsAccess, CaptureState.Off -> SentinelIcons.ShieldAlert
    }
    when (state) {
        CaptureState.Active -> {
            title = stringResource(R.string.capture_card_active_title)
            val watching = pluralStringResource(R.plurals.capture_watching_apps, numbers.watchedApps, numbers.watchedApps)
            val last = numbers.stats.lastCapturedAt?.let {
                stringResource(R.string.capture_last_event, relativeTimeText(Instant.ofEpochMilli(it), now))
            } ?: stringResource(R.string.capture_no_events_yet)
            body = stringResource(R.string.capture_card_active_body, watching, last, numbers.queued)
        }
        CaptureState.Paused -> {
            title = stringResource(R.string.capture_card_paused_title)
            body = stringResource(R.string.capture_card_paused_body)
        }
        CaptureState.NeedsAccess -> {
            title = stringResource(R.string.capture_card_access_title)
            body = stringResource(R.string.capture_card_access_body)
        }
        CaptureState.Off -> {
            title = stringResource(R.string.capture_card_off_title)
            body = stringResource(R.string.capture_card_off_body)
        }
    }
    val tone = when (state) {
        CaptureState.Active -> p.accent
        CaptureState.Paused -> p.secondaryLabel
        else -> SentinelTheme.colors[Tier.Caution].content
    }
    Row(
        modifier.fillMaxWidth().glass(RoundedCornerShape(Radii.large)).padding(Spacing.x2),
        horizontalArrangement = Arrangement.spacedBy(Spacing.x15),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconTile(icon, tint = tone)
        Column(Modifier.weight(1f)) {
            Text(title, style = SentinelTheme.type.headline, color = p.label, modifier = Modifier.semantics { heading() })
            Text(body, style = SentinelTheme.type.subhead, color = p.secondaryLabel)
        }
        if (state != CaptureState.Active) {
            PlainButton(
                stringResource(if (state == CaptureState.Off) R.string.capture_turn_on else R.string.source_fix),
                onFix,
            )
        }
    }
}
