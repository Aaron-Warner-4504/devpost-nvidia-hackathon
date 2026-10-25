package ai.enthral.sentinel.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import ai.enthral.sentinel.R
import ai.enthral.sentinel.data.api.ApiResult
import ai.enthral.sentinel.ui.theme.Radii
import ai.enthral.sentinel.ui.theme.SentinelTheme
import ai.enthral.sentinel.ui.theme.Spacing
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** Calm empty state: an illustration, one title, one sentence, at most one action. */
@Composable
fun EmptyState(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    illustration: @Composable () -> Unit = { ShieldIllustration(size = 132.dp, breathing = false) },
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = Spacing.x4, vertical = Spacing.x4),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        illustration()
        Spacer(Modifier.height(Spacing.x25))
        Text(
            title,
            style = SentinelTheme.type.title3,
            color = SentinelTheme.palette.label,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { heading() },
        )
        Spacer(Modifier.height(Spacing.x1))
        Text(body, style = SentinelTheme.type.callout, color = SentinelTheme.palette.secondaryLabel, textAlign = TextAlign.Center)
        if (action != null) Box(Modifier.padding(top = Spacing.x25)) { action() }
    }
}

@Composable
fun ErrorState(message: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    EmptyState(
        title = stringResource(R.string.error_title),
        body = message,
        modifier = modifier,
        illustration = {
            Box(Modifier.size(72.dp).glass(CircleShape), contentAlignment = Alignment.Center) {
                Icon(SentinelIcons.CloudOff, null, tint = SentinelTheme.palette.secondaryLabel, modifier = Modifier.size(32.dp))
            }
        },
        action = { SecondaryButton(stringResource(R.string.action_retry), onClick = onRetry, icon = SentinelIcons.Refresh) },
    )
}

/** Shimmering placeholders shaped like the content they stand in for; announced once as "Loading". */
@Composable
fun LoadingSkeleton(modifier: Modifier = Modifier, rows: Int = 3) {
    val loading = stringResource(R.string.loading_cd)
    val shape = RoundedCornerShape(Radii.large)
    Column(
        modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = loading },
        verticalArrangement = Arrangement.spacedBy(Spacing.x15),
    ) {
        repeat(rows) {
            Row(
                Modifier.fillMaxWidth().shimmer(shape).padding(Spacing.x2),
                horizontalArrangement = Arrangement.spacedBy(Spacing.x15),
            ) {
                Box(Modifier.size(36.dp).shimmerDot())
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.x1)) {
                    Box(Modifier.fillMaxWidth(0.4f).height(12.dp).shimmerBar())
                    Box(Modifier.fillMaxWidth(0.9f).height(14.dp).shimmerBar())
                    Box(Modifier.fillMaxWidth(0.7f).height(14.dp).shimmerBar())
                }
            }
        }
    }
}

@Composable
private fun Modifier.shimmerBar() = shimmer(RoundedCornerShape(6.dp))

@Composable
private fun Modifier.shimmerDot() = shimmer(CircleShape)

/**
 * Quiet note: a small icon and footnote text, no card (privacy notes, offline notes). Kept as `NoteCard` for its
 * call sites; it no longer draws a card.
 */
@Composable
fun NoteCard(text: String, modifier: Modifier = Modifier, icon: ImageVector = SentinelIcons.Lock) {
    Row(
        modifier.fillMaxWidth().padding(horizontal = Spacing.gutter, vertical = Spacing.x1),
        horizontalArrangement = Arrangement.spacedBy(Spacing.x1),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(icon, null, tint = SentinelTheme.palette.secondaryLabel, modifier = Modifier.padding(top = 1.dp).size(16.dp))
        Text(text, style = SentinelTheme.type.footnote, color = SentinelTheme.palette.secondaryLabel, modifier = Modifier.weight(1f))
    }
}

/** Inline status line in a tone colour (errors, success) with an icon so it is never colour-only. */
@Composable
fun StatusLine(text: String, icon: ImageVector, color: androidx.compose.ui.graphics.Color, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.x1), verticalAlignment = Alignment.Top) {
        Icon(icon, null, tint = color, modifier = Modifier.padding(top = 2.dp).size(18.dp))
        Spacer(Modifier.width(0.dp))
        Text(text, style = SentinelTheme.type.subhead, color = color, modifier = Modifier.weight(1f))
    }
}

private val timestampFormatter: DateTimeFormatter =
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)

private val timeFormatter: DateTimeFormatter = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)

/** Formats in the device zone and the current app locale. */
fun formatTimestamp(instant: java.time.Instant, locale: Locale = Locale.getDefault()): String =
    timestampFormatter.withLocale(locale).withZone(ZoneId.systemDefault()).format(instant)

/** Time of day only (rows already sit under a date header). */
fun formatTime(instant: java.time.Instant, locale: Locale = Locale.getDefault()): String =
    timeFormatter.withLocale(locale).withZone(ZoneId.systemDefault()).format(instant)

/** Maps a failed [ApiResult] to a user-facing message. */
@Composable
fun apiErrorMessage(result: ApiResult<*>): String = when (result) {
    is ApiResult.NetworkError -> stringResource(R.string.error_network)
    is ApiResult.HttpError -> when {
        result.isUnauthorized -> stringResource(R.string.error_unauthorized)
        result.isRateLimited -> stringResource(R.string.error_rate_limited)
        else -> stringResource(R.string.error_server, result.status)
    }
    is ApiResult.Success -> ""
}
