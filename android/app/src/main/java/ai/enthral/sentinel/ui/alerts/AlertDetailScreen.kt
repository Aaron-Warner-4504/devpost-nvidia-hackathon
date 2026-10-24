package ai.enthral.sentinel.ui.alerts

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ai.enthral.sentinel.R
import ai.enthral.sentinel.domain.Alert
import ai.enthral.sentinel.domain.FeedbackLabel
import ai.enthral.sentinel.domain.Tier
import ai.enthral.sentinel.ui.common.AdaptiveBox
import ai.enthral.sentinel.ui.common.graphViewModel
import ai.enthral.sentinel.ui.components.EmptyState
import ai.enthral.sentinel.ui.components.FeedbackBar
import ai.enthral.sentinel.ui.components.LoadingSkeleton
import ai.enthral.sentinel.ui.components.NoteCard
import ai.enthral.sentinel.ui.components.PrimaryButton
import ai.enthral.sentinel.ui.components.SecondaryButton
import ai.enthral.sentinel.ui.components.SectionHeader
import ai.enthral.sentinel.ui.components.SignalChips
import ai.enthral.sentinel.ui.components.WhyTimeline
import ai.enthral.sentinel.ui.components.formatTimestamp
import ai.enthral.sentinel.ui.components.presentation
import ai.enthral.sentinel.ui.theme.SentinelTheme
import ai.enthral.sentinel.ui.theme.Spacing
import java.time.Instant
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlertDetailScreen(alertId: String, onBack: () -> Unit) {
    val vm = graphViewModel(key = "alert-$alertId") { AlertDetailViewModel.create(it, alertId) }
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val undoLabel = stringResource(R.string.action_undo)
    val noted = stringResource(R.string.feedback_noted)
    val failed = stringResource(R.string.feedback_failed)
    LaunchedEffect(vm) {
        vm.events.flow.collect { e ->
            when (e) {
                is DetailEvent.ShowUndo -> {
                    val r = snackbar.showSnackbar(noted, actionLabel = undoLabel, duration = SnackbarDuration.Short)
                    if (r == SnackbarResult.ActionPerformed) vm.undo()
                }
                DetailEvent.FeedbackFailed -> snackbar.showSnackbar(failed)
            }
        }
    }
    val content = state
    val title = if ((content as? DetailState.Content)?.alert?.tier == Tier.Info) R.string.detail_title_result else R.string.detail_title
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(title), modifier = Modifier.semantics { heading() }) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        AdaptiveBox(Modifier.padding(padding)) {
            when (content) {
                DetailState.Loading -> Column(Modifier.padding(Spacing.gutter)) { LoadingSkeleton(rows = 3) }
                DetailState.NotFound -> EmptyState(
                    stringResource(R.string.detail_not_found_title),
                    stringResource(R.string.detail_not_found_body),
                    action = { SecondaryButton(stringResource(R.string.action_back), onBack) },
                )
                is DetailState.Content -> AlertDetailBody(
                    alert = content.alert,
                    reaction = content.reaction,
                    now = remember { Instant.now() },
                    onFeedback = vm::onFeedback,
                    snackbar = snackbar,
                )
            }
        }
    }
}

@Composable
fun AlertDetailBody(
    alert: Alert,
    reaction: FeedbackLabel?,
    now: Instant,
    onFeedback: (FeedbackLabel) -> Unit,
    snackbar: SnackbarHostState? = null,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val tierLabel = stringResource(alert.tier.presentation().label)
    val summary = stringResource(R.string.share_summary, tierLabel, alert.headline, alert.nextStep)
    val copied = stringResource(R.string.detail_copied)
    val chooser = stringResource(R.string.detail_share_chooser)
    val isInfo = alert.tier == Tier.Info

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = Spacing.gutter, vertical = Spacing.x1),
        verticalArrangement = Arrangement.spacedBy(Spacing.x1),
    ) {
        item { TierHero(alert) }
        item { SectionHeader(stringResource(if (isInfo) R.string.detail_why_fine else R.string.detail_why)) }
        item { WhyTimeline(alert, now) }
        item { SectionHeader(stringResource(R.string.detail_what_to_do)) }
        item {
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
            ) {
                Column(Modifier.padding(Spacing.x2), verticalArrangement = Arrangement.spacedBy(Spacing.x2)) {
                    Text(alert.nextStep, style = MaterialTheme.typography.bodyLarge)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.x1)) {
                        PrimaryButton(
                            stringResource(R.string.detail_share_summary),
                            {
                                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, summary)
                                context.startActivity(Intent.createChooser(send, chooser))
                            },
                            Modifier.weight(1f),
                        )
                        SecondaryButton(
                            stringResource(R.string.detail_copy_summary),
                            {
                                clipboard.setText(AnnotatedString(summary))
                                snackbar?.let { scope.launch { it.showSnackbar(copied) } }
                            },
                            Modifier.weight(1f),
                            iconRes = R.drawable.ic_copy,
                        )
                    }
                }
            }
        }
        if (alert.signals.isNotEmpty()) {
            item { SectionHeader(stringResource(R.string.detail_signals)) }
            item { SignalChips(alert.signals) }
        }
        item { NoteCard(stringResource(R.string.detail_privacy_note)) }
        item {
            Text(
                stringResource(R.string.detail_created, formatTimestamp(alert.createdAt)),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = Spacing.x1),
            )
        }
        item { FeedbackBar(reaction, onFeedback, Modifier.padding(vertical = Spacing.x2)) }
    }
}

@Composable
private fun TierHero(alert: Alert) {
    val colors = SentinelTheme.colors[alert.tier]
    val p = alert.tier.presentation()
    val isInfo = alert.tier == Tier.Info
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = colors.container, contentColor = colors.content),
        shape = MaterialTheme.shapes.large,
    ) {
        Column(Modifier.padding(Spacing.x3), verticalArrangement = Arrangement.spacedBy(Spacing.x1)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.x1)) {
                Icon(painterResource(p.icon), contentDescription = null, modifier = Modifier.size(32.dp))
                Text(
                    stringResource(if (isInfo) R.string.detail_looks_fine else p.label),
                    style = MaterialTheme.typography.titleLarge,
                )
            }
            Text(alert.headline, style = MaterialTheme.typography.headlineSmall)
        }
    }
}
