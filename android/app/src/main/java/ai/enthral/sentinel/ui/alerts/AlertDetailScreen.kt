package ai.enthral.sentinel.ui.alerts

import android.content.Intent
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ai.enthral.sentinel.R
import ai.enthral.sentinel.domain.Alert
import ai.enthral.sentinel.domain.FeedbackLabel
import ai.enthral.sentinel.domain.Tier
import ai.enthral.sentinel.ui.common.MaxContentWidth
import ai.enthral.sentinel.ui.common.graphViewModel
import ai.enthral.sentinel.ui.components.EmptyState
import ai.enthral.sentinel.ui.components.FeedbackBar
import ai.enthral.sentinel.ui.components.LargeTitleColumn
import ai.enthral.sentinel.ui.components.LoadingSkeleton
import ai.enthral.sentinel.ui.components.NoteCard
import ai.enthral.sentinel.ui.components.PlainButton
import ai.enthral.sentinel.ui.components.PrimaryButton
import ai.enthral.sentinel.ui.components.SecondaryButton
import ai.enthral.sentinel.ui.components.SectionHeader
import ai.enthral.sentinel.ui.components.SentinelIcons
import ai.enthral.sentinel.ui.components.SentinelSnackbarHost
import ai.enthral.sentinel.ui.components.SignalChips
import ai.enthral.sentinel.ui.components.TierGlyph
import ai.enthral.sentinel.ui.components.WhyTimeline
import ai.enthral.sentinel.ui.components.glass
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import ai.enthral.sentinel.ui.components.formatTimestamp
import ai.enthral.sentinel.ui.components.presentation
import ai.enthral.sentinel.ui.components.rememberHaptics
import ai.enthral.sentinel.ui.theme.Radii
import ai.enthral.sentinel.ui.theme.SentinelTheme
import ai.enthral.sentinel.ui.theme.Spacing
import java.time.Instant
import kotlinx.coroutines.launch

@Composable
fun AlertDetailScreen(alertId: String, onBack: (() -> Unit)?) {
    val vm = graphViewModel(key = "alert-$alertId") { AlertDetailViewModel.create(it, alertId) }
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val haptics = rememberHaptics()
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
    val alertTier = (content as? DetailState.Content)?.alert?.tier
    // A critical alert announces itself with a warning haptic, once, when it opens.
    LaunchedEffect(alertTier == Tier.Critical) { if (alertTier == Tier.Critical) haptics.reject() }
    val title = if (alertTier == Tier.Info) R.string.detail_title_result else R.string.detail_title
    val scroll = rememberScrollState()
    Box(Modifier.fillMaxSize()) {
        LargeTitleColumn(title = stringResource(title), onBack = onBack, scroll = scroll, collapseAfter = 120.dp) { padding ->
            when (content) {
                DetailState.Loading -> Column(Modifier.padding(padding).padding(Spacing.inset)) { LoadingSkeleton(rows = 3) }
                DetailState.NotFound -> Box(Modifier.padding(padding)) {
                    EmptyState(
                        stringResource(R.string.detail_not_found_title),
                        stringResource(R.string.detail_not_found_body),
                        action = { if (onBack != null) SecondaryButton(stringResource(R.string.action_back), onBack) },
                    )
                }
                is DetailState.Content -> AlertDetailBody(
                    alert = content.alert,
                    reaction = content.reaction,
                    now = remember { Instant.now() },
                    onFeedback = vm::onFeedback,
                    snackbar = snackbar,
                    scroll = scroll,
                    contentPadding = padding,
                )
            }
        }
        SentinelSnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding())
    }
}

/**
 * The key screen: verdict as a large title next to the tier glyph, the "why" timeline, one clear next step,
 * signals, then feedback and the privacy footnote. A plain scrolling column (bounded content), so every part is
 * composed and reachable by TalkBack and tests.
 */
@Composable
fun AlertDetailBody(
    alert: Alert,
    reaction: FeedbackLabel?,
    now: Instant,
    onFeedback: (FeedbackLabel) -> Unit,
    snackbar: SnackbarHostState? = null,
    scroll: ScrollState = rememberScrollState(),
    contentPadding: PaddingValues = PaddingValues(),
) {
    val p = SentinelTheme.palette
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val haptics = rememberHaptics()
    val tierLabel = stringResource(alert.tier.presentation().label)
    val summary = stringResource(R.string.share_summary, tierLabel, alert.headline, alert.nextStep)
    val copied = stringResource(R.string.detail_copied)
    val chooser = stringResource(R.string.detail_share_chooser)
    val isInfo = alert.tier == Tier.Info

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.widthIn(max = MaxContentWidth).fillMaxWidth().verticalScroll(scroll)
                .padding(contentPadding).padding(bottom = Spacing.x6).navigationBarsPadding(),
        ) {
            VerdictHeader(alert, isInfo, now)

            SectionHeader(stringResource(if (isInfo) R.string.detail_why_fine else R.string.detail_why))
            Card { WhyTimeline(alert, now) }

            SectionHeader(stringResource(R.string.detail_what_to_do))
            Card {
                Text(alert.nextStep, style = SentinelTheme.type.bodyEmphasis, color = p.label)
                Spacer(Modifier.height(Spacing.x25))
                PrimaryButton(
                    stringResource(R.string.detail_share_summary),
                    {
                        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, summary)
                        context.startActivity(Intent.createChooser(send, chooser))
                    },
                    Modifier.fillMaxWidth(),
                    icon = SentinelIcons.Share,
                )
                PlainButton(
                    stringResource(R.string.detail_copy_summary),
                    {
                        clipboard.setText(AnnotatedString(summary))
                        haptics.confirm()
                        snackbar?.let { scope.launch { it.showSnackbar(copied) } }
                    },
                    Modifier.fillMaxWidth().padding(top = Spacing.x05),
                )
            }

            if (alert.signals.isNotEmpty()) {
                SectionHeader(stringResource(R.string.detail_signals))
                SignalChips(alert.signals, Modifier.padding(horizontal = Spacing.inset))
            }

            FeedbackBar(reaction, onFeedback, Modifier.padding(start = Spacing.inset, end = Spacing.inset, top = Spacing.x5, bottom = Spacing.x3))

            NoteCard(stringResource(R.string.detail_privacy_note))
            Text(
                stringResource(R.string.detail_created, formatTimestamp(alert.createdAt)),
                style = SentinelTheme.type.footnote,
                color = p.secondaryLabel,
                modifier = Modifier.padding(horizontal = Spacing.gutter, vertical = Spacing.x05).padding(start = 24.dp),
            )
        }
    }
}

/**
 * The verdict as a glass hero: tier glyph, tier word, headline as the title. A critical verdict gets a calm
 * crimson luminous edge and glow (the screen itself stays calm); others a plain glass card.
 */
@Composable
private fun VerdictHeader(alert: Alert, isInfo: Boolean, now: Instant) {
    val tier = SentinelTheme.colors[alert.tier]
    val glow = if (alert.tier == Tier.Critical) tier.content.copy(alpha = 0.6f) else null
    Column(
        Modifier.fillMaxWidth().padding(horizontal = Spacing.inset).padding(top = Spacing.x1)
            .glass(RoundedCornerShape(Radii.extraLarge), glow = glow)
            .drawBehind {
                val c = androidx.compose.ui.geometry.Offset(52.dp.toPx(), 52.dp.toPx())
                val r = size.width * 0.8f
                drawCircle(Brush.radialGradient(listOf(tier.content.copy(alpha = 0.16f), tier.content.copy(alpha = 0f)), c, r), r, c)
            }
            .padding(Spacing.x25),
    ) {
        TierGlyph(alert.tier, size = 64.dp, announce = false)
        Spacer(Modifier.height(Spacing.x2))
        Text(
            stringResource(if (isInfo) R.string.detail_looks_fine else alert.tier.presentation().label),
            style = SentinelTheme.type.headline.copy(fontWeight = FontWeight.SemiBold),
            color = tier.content,
        )
        Spacer(Modifier.height(Spacing.x05))
        Text(
            alert.headline,
            style = SentinelTheme.type.title1,
            color = SentinelTheme.palette.label,
            modifier = Modifier.semantics { heading() },
        )
    }
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = Spacing.inset).glass(RoundedCornerShape(Radii.large)).padding(Spacing.x2),
        verticalArrangement = Arrangement.Top,
    ) { content() }
}
