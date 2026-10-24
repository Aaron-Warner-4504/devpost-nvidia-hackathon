package ai.enthral.sentinel.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import ai.enthral.sentinel.R
import ai.enthral.sentinel.data.api.ApiResult
import ai.enthral.sentinel.domain.Alert
import ai.enthral.sentinel.domain.Tier
import ai.enthral.sentinel.ui.theme.SentinelTheme
import ai.enthral.sentinel.ui.theme.Spacing
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** Icon + text label on the tier's container colour; announced as a single "Risk level: X" item. */
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
        shape = RoundedCornerShape(percent = 50),
    ) {
        Row(
            Modifier.padding(horizontal = Spacing.x2, vertical = Spacing.x05),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.x1),
        ) {
            Icon(painterResource(p.icon), contentDescription = null, modifier = Modifier.size(18.dp))
            Text(label, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
fun AlertCard(alert: Alert, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = SentinelTheme.colors[alert.tier]
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().heightIn(min = Spacing.minTouchTarget),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
        border = androidx.compose.foundation.BorderStroke(1.dp, colors.content.copy(alpha = 0.35f)),
    ) {
        Column(Modifier.padding(Spacing.x2), verticalArrangement = Arrangement.spacedBy(Spacing.x1)) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                TierBadge(alert.tier)
                Text(
                    formatTimestamp(alert.createdAt),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = Spacing.x1),
                )
            }
            Text(alert.headline, style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.alert_next_step, alert.nextStep),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun EmptyState(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    iconRes: Int = R.drawable.ic_shield,
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier.fillMaxWidth().padding(Spacing.x3),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.x1),
    ) {
        Icon(
            painterResource(iconRes),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(48.dp),
        )
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (action != null) Box(Modifier.padding(top = Spacing.x1)) { action() }
    }
}

@Composable
fun ErrorState(message: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    EmptyState(
        title = stringResource(R.string.error_title),
        body = message,
        modifier = modifier,
        iconRes = R.drawable.ic_tier_high,
        action = { SecondaryButton(stringResource(R.string.action_retry), onClick = onRetry) },
    )
}

/** Pulsing placeholder rows; announced once as "Loading". */
@Composable
fun LoadingSkeleton(modifier: Modifier = Modifier, rows: Int = 3) {
    val transition = rememberInfiniteTransition(label = "skeleton")
    val alpha = transition.animateFloat(
        initialValue = 0.4f,
        targetValue = 0.9f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "alpha",
    )
    val loading = stringResource(R.string.loading_cd)
    Column(
        modifier.fillMaxWidth().semantics { contentDescription = loading },
        verticalArrangement = Arrangement.spacedBy(Spacing.x1),
    ) {
        repeat(rows) {
            Surface(
                Modifier.fillMaxWidth().height(88.dp).alpha(alpha.value),
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {}
        }
    }
}

@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = Spacing.minTouchTarget),
        shape = MaterialTheme.shapes.medium,
    ) { Text(text, style = MaterialTheme.typography.labelLarge) }
}

@Composable
fun SecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = Spacing.minTouchTarget),
        shape = MaterialTheme.shapes.medium,
    ) { Text(text, style = MaterialTheme.typography.labelLarge) }
}

@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = modifier.padding(top = Spacing.x2, bottom = Spacing.x1).semantics { heading() },
    )
}

private val timestampFormatter: DateTimeFormatter =
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)

/** Formats in the device zone and the current app locale. */
fun formatTimestamp(instant: java.time.Instant, locale: Locale = Locale.getDefault()): String =
    timestampFormatter.withLocale(locale).withZone(ZoneId.systemDefault()).format(instant)

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
