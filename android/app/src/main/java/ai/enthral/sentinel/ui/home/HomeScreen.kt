package ai.enthral.sentinel.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import ai.enthral.sentinel.R
import ai.enthral.sentinel.SentinelApp
import ai.enthral.sentinel.ui.components.AlertCard
import ai.enthral.sentinel.ui.components.EmptyState
import ai.enthral.sentinel.ui.components.ErrorState
import ai.enthral.sentinel.ui.components.LoadingSkeleton
import ai.enthral.sentinel.ui.components.SectionHeader
import ai.enthral.sentinel.ui.components.apiErrorMessage
import ai.enthral.sentinel.ui.theme.Spacing

@Composable
fun HomeScreen() {
    val app = LocalContext.current.applicationContext as SentinelApp
    val vm: HomeViewModel = viewModel(factory = HomeViewModel.factory(app.container.alerts))
    val state by vm.state.collectAsStateWithLifecycle()
    HomeContent(state = state, onRetry = vm::refresh)
}

@Composable
fun HomeContent(state: HomeUiState, onRetry: () -> Unit) {
    LazyColumn(
        modifier = Modifier.statusBarsPadding(),
        contentPadding = PaddingValues(horizontal = Spacing.gutter, vertical = Spacing.x2),
        verticalArrangement = Arrangement.spacedBy(Spacing.x1),
    ) {
        item { Text(
            stringResource(R.string.app_name),
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(bottom = Spacing.x1).semantics { heading() },
        ) }
        item { StatusCard() }
        item { SectionHeader(stringResource(R.string.home_recent_alerts)) }
        when (state) {
            HomeUiState.Loading -> item { LoadingSkeleton() }
            is HomeUiState.Error -> item { ErrorState(apiErrorMessage(state.failure), onRetry) }
            is HomeUiState.Content ->
                if (state.alerts.isEmpty()) {
                    item {
                        EmptyState(
                            title = stringResource(R.string.home_empty_title),
                            body = stringResource(R.string.home_empty_body),
                        )
                    }
                } else {
                    items(state.alerts.take(5), key = { it.id }) { alert -> AlertCard(alert, onClick = {}) }
                }
        }
    }
}

@Composable
private fun StatusCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
        shape = MaterialTheme.shapes.large,
    ) {
        Row(
            Modifier.padding(Spacing.x3),
            horizontalArrangement = Arrangement.spacedBy(Spacing.x2),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                painterResource(R.drawable.ic_shield),
                contentDescription = stringResource(R.string.home_shield_cd),
                modifier = Modifier.size(40.dp),
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.x05)) {
                Text(stringResource(R.string.home_status_title), style = MaterialTheme.typography.titleLarge)
                Text(stringResource(R.string.home_status_body), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
