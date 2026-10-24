package ai.enthral.sentinel.ui.alerts

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.size
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ai.enthral.sentinel.R
import ai.enthral.sentinel.ui.common.AdaptiveBox
import ai.enthral.sentinel.ui.common.DayBucket
import ai.enthral.sentinel.ui.common.graphViewModel
import ai.enthral.sentinel.ui.components.AlertCard
import ai.enthral.sentinel.ui.components.EmptyState
import ai.enthral.sentinel.ui.components.ErrorState
import ai.enthral.sentinel.ui.components.LoadingSkeleton
import ai.enthral.sentinel.ui.components.SecondaryButton
import ai.enthral.sentinel.ui.components.apiErrorMessage
import ai.enthral.sentinel.ui.home.Phase
import ai.enthral.sentinel.ui.theme.Spacing
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

@Composable
fun AlertsScreen(onOpenAlert: (String) -> Unit) {
    val vm = graphViewModel { AlertsViewModel.create(it) }
    val state by vm.state.collectAsStateWithLifecycle()
    AlertsContent(state, vm::setFilter, { vm.refresh() }, onOpenAlert)
}

@Composable
private fun filterLabel(f: AlertFilter) = stringResource(
    when (f) {
        AlertFilter.All -> R.string.filter_all
        AlertFilter.Critical -> R.string.tier_critical
        AlertFilter.High -> R.string.tier_high
        AlertFilter.Caution -> R.string.tier_caution
    },
)

@Composable
private fun bucketLabel(b: DayBucket): String = when (b) {
    DayBucket.Today -> stringResource(R.string.day_today)
    DayBucket.Yesterday -> stringResource(R.string.day_yesterday)
    is DayBucket.On -> DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(Locale.getDefault()).format(b.date)
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun AlertsContent(
    state: AlertsUiState,
    onFilter: (AlertFilter) -> Unit,
    onRefresh: () -> Unit,
    onOpenAlert: (String) -> Unit,
) {
    AdaptiveBox(Modifier.statusBarsPadding()) {
        Column {
            Text(
                stringResource(R.string.nav_alerts),
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(start = Spacing.gutter, end = Spacing.gutter, top = Spacing.x2).semantics { heading() },
            )
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = Spacing.gutter, vertical = Spacing.x1),
                horizontalArrangement = Arrangement.spacedBy(Spacing.x1),
            ) {
                AlertFilter.entries.forEach { f ->
                    FilterChip(
                        selected = state.filter == f,
                        onClick = { onFilter(f) },
                        label = { Text(filterLabel(f), style = MaterialTheme.typography.labelLarge) },
                        leadingIcon = if (state.filter == f) {
                            { Icon(Icons.Filled.Check, null, Modifier.size(18.dp)) }
                        } else null,
                    )
                }
            }
            PullToRefreshBox(isRefreshing = state.refreshing, onRefresh = onRefresh, modifier = Modifier.weight(1f)) {
                when {
                    state.phase == Phase.Loading -> Box(Modifier.padding(Spacing.gutter)) { LoadingSkeleton(rows = 4) }
                    state.phase == Phase.Error -> LazyColumn { item { ErrorState(apiErrorMessage(state.failure!!), onRefresh) } }
                    state.groups.isEmpty() -> LazyColumn {
                        item {
                            if (state.filteredOut) {
                                EmptyState(
                                    stringResource(R.string.alerts_filtered_title),
                                    stringResource(R.string.alerts_filtered_body),
                                    action = { SecondaryButton(stringResource(R.string.filter_show_all), { onFilter(AlertFilter.All) }) },
                                )
                            } else {
                                EmptyState(stringResource(R.string.home_empty_title), stringResource(R.string.alerts_empty_body))
                            }
                        }
                    }
                    else -> LazyColumn(
                        contentPadding = PaddingValues(horizontal = Spacing.gutter, vertical = Spacing.x1),
                        verticalArrangement = Arrangement.spacedBy(Spacing.x1),
                    ) {
                        state.groups.forEach { group ->
                            stickyHeader(key = "h-${group.bucket}") {
                                Surface(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.background) {
                                    Text(
                                        bucketLabel(group.bucket),
                                        style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(vertical = Spacing.x1).semantics { heading() },
                                    )
                                }
                            }
                            items(group.alerts, key = { it.id }) { alert -> AlertCard(alert, onClick = { onOpenAlert(alert.id) }) }
                        }
                    }
                }
            }
        }
    }
}
