package ai.enthral.sentinel.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import ai.enthral.sentinel.R
import ai.enthral.sentinel.domain.ProtectionStatus
import ai.enthral.sentinel.ui.theme.Spacing

private data class HeroStyle(
    val icon: Int, val title: Int, val body: Int, val action: Int?,
)

private fun ProtectionStatus.style() = when (this) {
    ProtectionStatus.Protected -> HeroStyle(R.drawable.ic_shield, R.string.status_protected_title, R.string.status_protected_body, null)
    ProtectionStatus.SetupIncomplete -> HeroStyle(R.drawable.ic_setup, R.string.status_setup_title, R.string.status_setup_body, R.string.status_setup_action)
    ProtectionStatus.Limited -> HeroStyle(R.drawable.ic_tier_high, R.string.status_limited_title, R.string.status_limited_body, R.string.status_limited_action)
    ProtectionStatus.Paused -> HeroStyle(R.drawable.ic_pause, R.string.status_paused_title, R.string.status_paused_body, R.string.status_paused_action)
    ProtectionStatus.Offline -> HeroStyle(R.drawable.ic_offline, R.string.status_offline_title, R.string.status_offline_body, R.string.action_retry)
}

/** Home hero. Meaning is carried by icon + title + text, with colour as reinforcement only. */
@Composable
fun StatusHero(
    status: ProtectionStatus,
    sourcesOn: String,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val s = status.style()
    val scheme = MaterialTheme.colorScheme
    val (container, content) = when (status) {
        ProtectionStatus.Protected -> scheme.tertiaryContainer to scheme.onTertiaryContainer
        ProtectionStatus.SetupIncomplete -> scheme.primaryContainer to scheme.onPrimaryContainer
        else -> scheme.secondaryContainer to scheme.onSecondaryContainer
    }
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = container, contentColor = content),
        shape = MaterialTheme.shapes.large,
    ) {
        Column(Modifier.padding(Spacing.x3), verticalArrangement = Arrangement.spacedBy(Spacing.x2)) {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.x2), verticalAlignment = Alignment.Top) {
                Icon(painterResource(s.icon), contentDescription = null, modifier = Modifier.size(40.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.x05)) {
                    Text(stringResource(s.title), style = MaterialTheme.typography.titleLarge)
                    Text(
                        if (status == ProtectionStatus.Protected) stringResource(s.body, sourcesOn) else stringResource(s.body),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            if (s.action != null) {
                PrimaryButton(stringResource(s.action), onAction, Modifier.fillMaxWidth())
            }
        }
    }
}

