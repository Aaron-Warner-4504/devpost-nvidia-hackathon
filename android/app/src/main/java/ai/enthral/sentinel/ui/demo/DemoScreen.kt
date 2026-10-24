package ai.enthral.sentinel.ui.demo

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ai.enthral.sentinel.R
import ai.enthral.sentinel.data.demo.DemoKey
import ai.enthral.sentinel.data.demo.DemoOutcome
import ai.enthral.sentinel.data.demo.DemoStep
import ai.enthral.sentinel.ui.common.AdaptiveBox
import ai.enthral.sentinel.ui.common.graphViewModel
import ai.enthral.sentinel.ui.components.NoteCard
import ai.enthral.sentinel.ui.components.PrimaryButton
import ai.enthral.sentinel.ui.components.SecondaryButton
import ai.enthral.sentinel.ui.components.TierBadge
import ai.enthral.sentinel.ui.components.apiErrorMessage
import ai.enthral.sentinel.ui.theme.Spacing
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DemoScreen(onBack: () -> Unit, onOpenAlert: (String) -> Unit) {
    val vm = graphViewModel { DemoViewModel.create(it) }
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(vm) { vm.events.flow.collect { if (it is DemoEvent.OpenAlert) onOpenAlert(it.id) } }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.demo_title), Modifier.semantics { heading() }) },
                navigationIcon = {
                    IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back)) }
                },
            )
        },
    ) { padding ->
        AdaptiveBox(Modifier.padding(padding)) {
            DemoContent(state, vm::start, onOpenAlert)
        }
    }
}

@Composable
fun DemoContent(state: DemoUiState, onStart: () -> Unit, onOpenAlert: (String) -> Unit) {
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = Spacing.gutter, vertical = Spacing.x2),
        verticalArrangement = Arrangement.spacedBy(Spacing.x2),
    ) {
        item {
            Surface(color = MaterialTheme.colorScheme.tertiaryContainer, contentColor = MaterialTheme.colorScheme.onTertiaryContainer, shape = MaterialTheme.shapes.small) {
                Text(stringResource(R.string.demo_label), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = Spacing.x2, vertical = Spacing.x05))
            }
        }
        item { Text(stringResource(R.string.demo_intro), style = MaterialTheme.typography.bodyLarge) }
        item { NoteCard(stringResource(R.string.demo_note), iconRes = R.drawable.ic_tier_info) }
        val steps = state.steps.ifEmpty { DemoKey.entries.map { DemoStep(it, DemoOutcome.Pending) } }
        items(steps.size) { i -> StepRow(i + 1, steps[i]) }
        item {
            when (state.phase) {
                DemoPhase.Idle -> PrimaryButton(stringResource(R.string.demo_run), onStart, Modifier.fillMaxWidth())
                DemoPhase.Running -> Text(stringResource(R.string.demo_running), style = MaterialTheme.typography.bodyMedium)
                DemoPhase.Done -> Column(verticalArrangement = Arrangement.spacedBy(Spacing.x1)) {
                    state.finalAlertId?.let { PrimaryButton(stringResource(R.string.demo_view_alert), { onOpenAlert(it) }, Modifier.fillMaxWidth()) }
                    SecondaryButton(stringResource(R.string.demo_run_again), onStart, Modifier.fillMaxWidth())
                    Text(stringResource(R.string.demo_idempotent), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                DemoPhase.Failed -> Column(verticalArrangement = Arrangement.spacedBy(Spacing.x1)) {
                    val failure = state.steps.firstNotNullOfOrNull { (it.outcome as? DemoOutcome.Failed)?.failure }
                    if (failure != null) Text(apiErrorMessage(failure), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                    PrimaryButton(stringResource(R.string.action_retry), onStart, Modifier.fillMaxWidth())
                }
            }
        }
    }
}

private fun DemoKey.titleRes() = when (this) {
    DemoKey.CourierSms -> R.string.demo_step_courier
    DemoKey.UnknownCall -> R.string.demo_step_call
    DemoKey.WhatsappUpi -> R.string.demo_step_whatsapp
}

@Composable
private fun StepRow(number: Int, step: DemoStep) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest)) {
        Row(Modifier.padding(Spacing.x2), horizontalArrangement = Arrangement.spacedBy(Spacing.x2), verticalAlignment = Alignment.Top) {
            Surface(Modifier.size(32.dp), shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer) {
                Column(Modifier.size(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    if (step.outcome == DemoOutcome.Running) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    else Text(number.toString(), style = MaterialTheme.typography.labelLarge)
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.x1)) {
                Text(stringResource(step.key.titleRes()), style = MaterialTheme.typography.titleSmall)
                when (val o = step.outcome) {
                    DemoOutcome.Pending -> Text(stringResource(R.string.demo_pending), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    DemoOutcome.Running -> Text(stringResource(R.string.demo_step_running), style = MaterialTheme.typography.bodySmall)
                    is DemoOutcome.Flagged -> {
                        TierBadge(o.alert.tier)
                        Text(o.alert.headline, style = MaterialTheme.typography.bodyMedium)
                    }
                    DemoOutcome.NoAlert -> Text(stringResource(R.string.demo_no_alert), style = MaterialTheme.typography.bodyMedium)
                    is DemoOutcome.Failed -> Text(stringResource(R.string.demo_step_failed), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}
