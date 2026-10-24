package ai.enthral.sentinel.ui.alerts

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ai.enthral.sentinel.R
import ai.enthral.sentinel.ui.common.DayBucket
import ai.enthral.sentinel.ui.common.graphViewModel
import ai.enthral.sentinel.ui.components.AlertRow
import ai.enthral.sentinel.ui.components.EmptyState
import ai.enthral.sentinel.ui.components.ErrorState
import ai.enthral.sentinel.ui.components.GroupedHeader
import ai.enthral.sentinel.ui.components.GroupedItem
import ai.enthral.sentinel.ui.components.LargeTitleList
import ai.enthral.sentinel.ui.components.LoadingSkeleton
import ai.enthral.sentinel.ui.components.SecondaryButton
import ai.enthral.sentinel.ui.components.ShieldIllustration
import ai.enthral.sentinel.ui.components.apiErrorMessage
import ai.enthral.sentinel.ui.components.presentation
import ai.enthral.sentinel.ui.components.rememberHaptics
import ai.enthral.sentinel.ui.home.Phase
import ai.enthral.sentinel.ui.theme.SentinelTheme
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
    is DayBucket.On -> DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withLocale(Locale.getDefault()).format(b.date)
}

@Composable
fun AlertsContent(
    state: AlertsUiState,
    onFilter: (AlertFilter) -> Unit,
    onRefresh: () -> Unit,
    onOpenAlert: (String) -> Unit,
) {
    LargeTitleList(
        title = stringResource(R.string.nav_alerts),
        refreshing = state.refreshing,
        onRefresh = onRefresh,
    ) {
        item(key = "filters") { FilterRow(state.filter, onFilter) }
        when {
            state.phase == Phase.Loading -> item(key = "loading") {
                LoadingSkeleton(Modifier.padding(horizontal = Spacing.inset, vertical = Spacing.x1), rows = 4)
            }
            state.phase == Phase.Error -> item(key = "error") { ErrorState(apiErrorMessage(state.failure!!), onRefresh) }
            state.groups.isEmpty() -> item(key = "empty") {
                if (state.filteredOut) {
                    EmptyState(
                        stringResource(R.string.alerts_filtered_title),
                        stringResource(R.string.alerts_filtered_body),
                        action = { SecondaryButton(stringResource(R.string.filter_show_all), { onFilter(AlertFilter.All) }) },
                    )
                } else {
                    EmptyState(
                        stringResource(R.string.home_empty_title),
                        stringResource(R.string.alerts_empty_body),
                        illustration = { ShieldIllustration(size = 140.dp) },
                    )
                }
            }
            else -> state.groups.forEach { group ->
                item(key = "h-${group.bucket}", contentType = "header") {
                    GroupedHeader(bucketLabel(group.bucket), Modifier.padding(horizontal = Spacing.inset).animateItem())
                }
                val count = group.alerts.size
                group.alerts.forEachIndexed { i, alert ->
                    item(key = alert.id, contentType = "alert") {
                        GroupedItem(i, count, Modifier.animateItem(), dividerInset = Spacing.x2 + 32.dp + Spacing.x15) {
                            AlertRow(alert, onClick = { onOpenAlert(alert.id) })
                        }
                    }
                }
            }
        }
    }
}

/** Minimal chips: selected is filled with the accent; tier chips carry their tier shape so colour is not alone. */
@Composable
private fun FilterRow(selected: AlertFilter, onFilter: (AlertFilter) -> Unit) {
    val p = SentinelTheme.palette
    val haptics = rememberHaptics()
    Row(
        Modifier.horizontalScroll(rememberScrollState()).selectableGroup()
            .padding(PaddingValues(horizontal = Spacing.inset, vertical = Spacing.x05)),
        horizontalArrangement = Arrangement.spacedBy(Spacing.x05),
    ) {
        AlertFilter.entries.forEach { f ->
            val isSelected = selected == f
            // 36-dp pill inside a 48-dp touch target.
            Box(
                Modifier.heightIn(min = Spacing.minTouchTarget)
                    .selectable(isSelected, role = Role.RadioButton, interactionSource = null, indication = null) {
                        if (!isSelected) haptics.tick(); onFilter(f)
                    },
                contentAlignment = Alignment.Center,
            ) {
            Row(
                Modifier.heightIn(min = 36.dp).clip(CircleShape)
                    .background(if (isSelected) p.accent else p.elevated)
                    .padding(horizontal = Spacing.x15 + 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                val tier = f.tier
                if (tier != null) {
                    Icon(
                        painterResource(tier.presentation().icon), null,
                        tint = if (isSelected) p.onAccent else SentinelTheme.colors[tier].content,
                        modifier = Modifier.size(16.dp),
                    )
                }
                Text(
                    filterLabel(f),
                    style = SentinelTheme.type.subhead.copy(fontWeight = FontWeight.SemiBold),
                    color = if (isSelected) p.onAccent else p.label,
                )
            }
            }
        }
    }
}
