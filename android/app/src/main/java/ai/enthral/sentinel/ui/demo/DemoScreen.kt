package ai.enthral.sentinel.ui.demo

import ai.enthral.sentinel.ui.components.glass
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ai.enthral.sentinel.R
import ai.enthral.sentinel.data.demo.DemoKey
import ai.enthral.sentinel.data.demo.DemoOutcome
import ai.enthral.sentinel.data.demo.DemoStep
import ai.enthral.sentinel.domain.Tier
import ai.enthral.sentinel.ui.common.graphViewModel
import ai.enthral.sentinel.ui.components.LargeTitleList
import ai.enthral.sentinel.ui.components.NoteCard
import ai.enthral.sentinel.ui.components.PlainButton
import ai.enthral.sentinel.ui.components.PrimaryButton
import ai.enthral.sentinel.ui.components.SentinelIcons
import ai.enthral.sentinel.ui.components.StatusLine
import ai.enthral.sentinel.ui.components.TierBadge
import ai.enthral.sentinel.ui.components.TierGlyph
import ai.enthral.sentinel.ui.components.apiErrorMessage
import ai.enthral.sentinel.ui.theme.Radii
import ai.enthral.sentinel.ui.theme.SentinelTheme
import ai.enthral.sentinel.ui.theme.Spacing

@Composable
fun DemoScreen(onBack: () -> Unit, onOpenAlert: (String) -> Unit) {
    val vm = graphViewModel { DemoViewModel.create(it) }
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(vm) { vm.events.flow.collect { if (it is DemoEvent.OpenAlert) onOpenAlert(it.id) } }
    DemoContent(state, vm::start, onOpenAlert, onBack)
}

private val Inset = Modifier.padding(horizontal = Spacing.inset)

@Composable
fun DemoContent(state: DemoUiState, onStart: () -> Unit, onOpenAlert: (String) -> Unit, onBack: (() -> Unit)? = null) {
    val p = SentinelTheme.palette
    LargeTitleList(title = stringResource(R.string.demo_title), onBack = onBack, subtitle = stringResource(R.string.demo_intro)) {
        item(key = "label") {
            Text(
                stringResource(R.string.demo_label),
                style = SentinelTheme.type.caption.copy(fontWeight = FontWeight.SemiBold),
                color = p.accent,
                modifier = Modifier.padding(horizontal = Spacing.gutter).clip(CircleShape).background(p.accentSoft)
                    .padding(horizontal = Spacing.x15, vertical = Spacing.x05),
            )
        }
        item(key = "steps") {
            val steps = state.steps.ifEmpty { DemoKey.entries.map { DemoStep(it, DemoOutcome.Pending) } }
            Column(
                Inset.padding(top = Spacing.x2).fillMaxWidth().glass(RoundedCornerShape(Radii.large))
                    .padding(horizontal = Spacing.x2, vertical = Spacing.x15),
            ) {
                steps.forEachIndexed { i, step -> StepRow(i + 1, step, first = i == 0, last = i == steps.lastIndex) }
            }
        }
        item(key = "note") { NoteCard(stringResource(R.string.demo_note), Modifier.padding(top = Spacing.x1), icon = SentinelIcons.Info) }
        item(key = "actions") {
            Column(Inset.padding(top = Spacing.x2), verticalArrangement = Arrangement.spacedBy(Spacing.x1)) {
                when (state.phase) {
                    DemoPhase.Idle -> PrimaryButton(stringResource(R.string.demo_run), onStart, Modifier.fillMaxWidth(), icon = SentinelIcons.Sparkles)
                    DemoPhase.Running -> PrimaryButton(stringResource(R.string.demo_running), {}, Modifier.fillMaxWidth(), enabled = false)
                    DemoPhase.Done -> {
                        state.finalAlertId?.let { PrimaryButton(stringResource(R.string.demo_view_alert), { onOpenAlert(it) }, Modifier.fillMaxWidth()) }
                        PlainButton(stringResource(R.string.demo_run_again), onStart, Modifier.fillMaxWidth())
                        Text(
                            stringResource(R.string.demo_idempotent),
                            style = SentinelTheme.type.footnote,
                            color = p.secondaryLabel,
                            modifier = Modifier.padding(horizontal = Spacing.x05),
                        )
                    }
                    DemoPhase.Failed -> {
                        val failure = state.steps.firstNotNullOfOrNull { (it.outcome as? DemoOutcome.Failed)?.failure }
                        if (failure != null) StatusLine(apiErrorMessage(failure), SentinelIcons.CloudOff, SentinelTheme.colors[Tier.Critical].content)
                        PrimaryButton(stringResource(R.string.action_retry), onStart, Modifier.fillMaxWidth())
                    }
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

/** One step of the vertical stepper: a node on a rail (number / spinner / outcome), then title and outcome. */
@Composable
private fun StepRow(number: Int, step: DemoStep, first: Boolean, last: Boolean) {
    val p = SentinelTheme.palette
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).semantics(mergeDescendants = true) {}) {
        Column(Modifier.width(36.dp).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.width(2.dp).height(Spacing.x1).background(if (first) androidx.compose.ui.graphics.Color.Transparent else p.separator))
            StepNode(number, step.outcome)
            if (!last) Box(Modifier.width(2.dp).weight(1f).background(p.separator))
        }
        Spacer(Modifier.width(Spacing.x15))
        Column(Modifier.weight(1f).padding(top = Spacing.x1 + 6.dp, bottom = Spacing.x25), verticalArrangement = Arrangement.spacedBy(Spacing.x1)) {
            Text(stringResource(step.key.titleRes()), style = SentinelTheme.type.headline, color = p.label)
            when (val o = step.outcome) {
                DemoOutcome.Pending -> Text(stringResource(R.string.demo_pending), style = SentinelTheme.type.subhead, color = p.secondaryLabel)
                DemoOutcome.Running -> Text(stringResource(R.string.demo_step_running), style = SentinelTheme.type.subhead, color = p.accent)
                is DemoOutcome.Flagged -> {
                    TierBadge(o.alert.tier)
                    Text(o.alert.headline, style = SentinelTheme.type.subhead, color = p.label)
                }
                DemoOutcome.NoAlert -> Text(stringResource(R.string.demo_no_alert), style = SentinelTheme.type.subhead, color = p.secondaryLabel)
                is DemoOutcome.Failed -> Text(stringResource(R.string.demo_step_failed), style = SentinelTheme.type.subhead, color = SentinelTheme.colors[Tier.Critical].content)
            }
        }
    }
}

@Composable
private fun StepNode(number: Int, outcome: DemoOutcome) {
    val p = SentinelTheme.palette
    when (outcome) {
        is DemoOutcome.Flagged -> TierGlyph(outcome.alert.tier, size = 36.dp)
        DemoOutcome.Running -> Box(Modifier.size(36.dp).background(p.accentSoft, CircleShape), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(Modifier.size(18.dp), color = p.accent, strokeWidth = 2.dp)
        }
        DemoOutcome.NoAlert -> Box(Modifier.size(36.dp).background(p.accentSoft, CircleShape), contentAlignment = Alignment.Center) {
            Icon(SentinelIcons.Checkmark, null, tint = p.accent, modifier = Modifier.size(18.dp))
        }
        is DemoOutcome.Failed -> Box(Modifier.size(36.dp).background(SentinelTheme.colors[Tier.Critical].container, CircleShape), contentAlignment = Alignment.Center) {
            Icon(SentinelIcons.Info, null, tint = SentinelTheme.colors[Tier.Critical].content, modifier = Modifier.size(18.dp))
        }
        DemoOutcome.Pending -> Box(Modifier.size(36.dp).border(1.5.dp, p.separator, CircleShape), contentAlignment = Alignment.Center) {
            Text(number.toString(), style = SentinelTheme.type.subhead.copy(fontWeight = FontWeight.SemiBold), color = p.secondaryLabel)
        }
    }
}
