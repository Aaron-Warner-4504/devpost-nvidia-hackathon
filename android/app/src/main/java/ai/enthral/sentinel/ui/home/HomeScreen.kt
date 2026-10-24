package ai.enthral.sentinel.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ai.enthral.sentinel.LocalDemoEnabled
import ai.enthral.sentinel.R
import ai.enthral.sentinel.domain.ConsentSource
import ai.enthral.sentinel.domain.ProtectionStatus
import ai.enthral.sentinel.domain.SourceStatus
import ai.enthral.sentinel.domain.Tier
import ai.enthral.sentinel.ui.common.AdaptiveBox
import ai.enthral.sentinel.ui.common.graphViewModel
import ai.enthral.sentinel.ui.components.AlertCard
import ai.enthral.sentinel.ui.components.EmptyState
import ai.enthral.sentinel.ui.components.ErrorState
import ai.enthral.sentinel.ui.components.LoadingSkeleton
import ai.enthral.sentinel.ui.components.NoteCard
import ai.enthral.sentinel.ui.components.PrimaryButton
import ai.enthral.sentinel.ui.components.SecondaryButton
import ai.enthral.sentinel.ui.components.SectionHeader
import ai.enthral.sentinel.ui.components.StatusHero
import ai.enthral.sentinel.ui.components.TierCountChip
import ai.enthral.sentinel.ui.components.apiErrorMessage
import ai.enthral.sentinel.ui.components.titleRes
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

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
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
    AdaptiveBox(Modifier.statusBarsPadding()) {
        PullToRefreshBox(isRefreshing = state.refreshing, onRefresh = onRefresh) {
            LazyColumn(
                contentPadding = PaddingValues(horizontal = Spacing.gutter, vertical = Spacing.x2),
                verticalArrangement = Arrangement.spacedBy(Spacing.x1),
            ) {
                item {
                    Text(
                        stringResource(R.string.app_name),
                        style = MaterialTheme.typography.headlineMedium,
                        modifier = Modifier.padding(bottom = Spacing.x1).semantics { heading() },
                    )
                }
                item {
                    StatusHero(
                        status = state.protection,
                        sourcesOn = sourcesOn,
                        onAction = if (state.protection == ProtectionStatus.Offline) onRefresh else onOpenSettings,
                    )
                }
                if (state.offline && state.phase == Phase.Ready) {
                    item { NoteCard(stringResource(R.string.home_offline_note), iconRes = R.drawable.ic_offline) }
                }
                item { SectionHeader(stringResource(R.string.home_today)) }
                item {
                    Card(
                        Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
                    ) {
                        val counts = Tier.entries.reversed().filter { (state.todayCounts[it] ?: 0) > 0 }
                        androidx.compose.foundation.layout.Column(
                            Modifier.padding(Spacing.x2),
                            verticalArrangement = Arrangement.spacedBy(Spacing.x1),
                        ) {
                            Text(
                                stringResource(if (counts.isEmpty()) R.string.home_today_none else R.string.home_today_some),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            if (counts.isNotEmpty()) {
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(Spacing.x1),
                                    verticalArrangement = Arrangement.spacedBy(Spacing.x1),
                                ) { counts.forEach { TierCountChip(it, state.todayCounts.getValue(it)) } }
                            }
                        }
                    }
                }
                item { SectionHeader(stringResource(R.string.home_latest_alert)) }
                when (state.phase) {
                    Phase.Loading -> item { LoadingSkeleton(rows = 1) }
                    Phase.Error -> item { ErrorState(apiErrorMessage(state.failure!!), onRefresh) }
                    Phase.Ready -> {
                        val latest = state.latest
                        if (latest == null) {
                            item {
                                EmptyState(
                                    title = stringResource(R.string.home_empty_title),
                                    body = stringResource(R.string.home_empty_body),
                                )
                            }
                        } else {
                            item(key = latest.id) { AlertCard(latest, onClick = { onOpenAlert(latest.id) }) }
                        }
                    }
                }
                item { SectionHeader(stringResource(R.string.home_quick_actions)) }
                item {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.x1)) {
                        PrimaryButton(stringResource(R.string.home_action_check), onOpenCheck, Modifier.weight(1f))
                        if (demoEnabled) {
                            SecondaryButton(stringResource(R.string.home_action_demo), onOpenDemo, Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}
