package ai.enthral.sentinel.ui.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ai.enthral.sentinel.R
import ai.enthral.sentinel.domain.ConsentSource
import ai.enthral.sentinel.domain.Tier
import ai.enthral.sentinel.ui.common.MaxContentWidth
import ai.enthral.sentinel.ui.common.graphViewModel
import ai.enthral.sentinel.ui.common.rememberPostNotificationsRequester
import ai.enthral.sentinel.ui.common.rememberReducedMotion
import ai.enthral.sentinel.ui.common.rememberSystemRequester
import ai.enthral.sentinel.ui.components.ConsentSheet
import ai.enthral.sentinel.ui.components.GroupedDivider
import ai.enthral.sentinel.ui.components.GroupedRow
import ai.enthral.sentinel.ui.components.GroupedSection
import ai.enthral.sentinel.ui.components.IconRowDividerInset
import ai.enthral.sentinel.ui.components.IconTile
import ai.enthral.sentinel.ui.components.LanguageIllustration
import ai.enthral.sentinel.ui.components.MaskedMessagesIllustration
import ai.enthral.sentinel.ui.components.PageIndicator
import ai.enthral.sentinel.ui.components.PlainButton
import ai.enthral.sentinel.ui.components.PrimaryButton
import ai.enthral.sentinel.ui.components.SegmentedControl
import ai.enthral.sentinel.ui.components.SentinelIcons
import ai.enthral.sentinel.ui.components.ShieldIllustration
import ai.enthral.sentinel.ui.components.SourceRow
import ai.enthral.sentinel.ui.components.StatusLine
import ai.enthral.sentinel.ui.components.apiErrorMessage
import ai.enthral.sentinel.ui.components.AtmosphereBox
import ai.enthral.sentinel.ui.components.glass
import ai.enthral.sentinel.ui.theme.Radii
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.graphicsLayer
import ai.enthral.sentinel.ui.settings.Languages
import ai.enthral.sentinel.ui.theme.SentinelTheme
import ai.enthral.sentinel.ui.theme.Spacing
import kotlinx.coroutines.launch

private const val PAGES = 4
private const val PAGE_LANGUAGE = 2
private const val PAGE_SOURCES = 3

@Composable
fun OnboardingScreen() {
    val vm = graphViewModel { OnboardingViewModel.create(it) }
    val state by vm.state.collectAsStateWithLifecycle()
    val requester = rememberSystemRequester(vm::onCallPermissionResult)
    val askPostNotifications = rememberPostNotificationsRequester { vm.onResume() }
    LaunchedEffect(vm) { vm.requests.flow.collect(requester) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.onResume() }

    val pager = rememberPagerState { PAGES }
    val scope = rememberCoroutineScope()
    val reduced = rememberReducedMotion()
    var sheet by rememberSaveable { mutableStateOf<String?>(null) }
    val go: (Int) -> Unit = { target ->
        scope.launch { if (reduced) pager.scrollToPage(target) else pager.animateScrollToPage(target) }
    }
    androidx.activity.compose.BackHandler(enabled = pager.currentPage > 0) { go(pager.currentPage - 1) }
    val p = SentinelTheme.palette

    AtmosphereBox { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = MaxContentWidth).fillMaxSize().safeDrawingPadding()) {
            // Top: back on the left, skip (language only) on the right. Quiet chrome.
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = Spacing.x05), verticalAlignment = Alignment.CenterVertically) {
                if (pager.currentPage > 0) {
                    IconButton({ go(pager.currentPage - 1) }) {
                        Icon(SentinelIcons.Back, stringResource(R.string.action_back), tint = p.accent)
                    }
                }
                Spacer(Modifier.weight(1f))
                // Language is the only step that is safe to skip (the device language stays in effect).
                if (pager.currentPage == PAGE_LANGUAGE) PlainButton(stringResource(R.string.action_skip), { go(PAGE_SOURCES) })
            }
            HorizontalPager(pager, Modifier.weight(1f)) { page ->
                when (page) {
                    0 -> CenteredPage {
                        ShieldIllustration(size = 200.dp, modifier = Modifier.parallax(pager, page))
                        Spacer(Modifier.height(Spacing.x4))
                        PageTitle(stringResource(R.string.onboarding_welcome_title), center = true)
                        Spacer(Modifier.height(Spacing.x15))
                        PageBody(stringResource(R.string.onboarding_welcome_body), center = true)
                    }
                    1 -> CenteredPage {
                        MaskedMessagesIllustration(size = 160.dp, modifier = Modifier.parallax(pager, page))
                        Spacer(Modifier.height(Spacing.x3))
                        PageTitle(stringResource(R.string.onboarding_privacy_title), center = true)
                        Spacer(Modifier.height(Spacing.x3))
                        Column(Modifier.fillMaxWidth().glass(RoundedCornerShape(Radii.large)).padding(Spacing.x2)) {
                            Promise(SentinelIcons.Hand, R.string.promise_1_title, R.string.promise_1_body)
                            Promise(SentinelIcons.EyeOff, R.string.promise_2_title, R.string.promise_2_body)
                            Promise(SentinelIcons.Trash, R.string.promise_3_title, R.string.promise_3_body)
                        }
                    }
                    PAGE_LANGUAGE -> CenteredPage {
                        LanguageIllustration(size = 168.dp, modifier = Modifier.parallax(pager, page))
                        Spacer(Modifier.height(Spacing.x4))
                        PageTitle(stringResource(R.string.onboarding_language_title), center = true)
                        Spacer(Modifier.height(Spacing.x15))
                        PageBody(stringResource(R.string.onboarding_language_body), center = true)
                        Spacer(Modifier.height(Spacing.x4))
                        SegmentedControl(
                            options = Languages.map { it.first },
                            selected = vm.language(),
                            onSelect = vm::setLanguage,
                            label = { tag -> Languages.first { it.first == tag }.second },
                        )
                    }
                    else -> SourcesPage(
                        state = state,
                        onToggle = { source, on -> if (on) sheet = source.name else vm.setSource(source, false) },
                        onInfo = { sheet = it.name },
                        onFix = vm::fixSource,
                        onAllowNotifications = askPostNotifications,
                    )
                }
            }
            // Bottom: one confident action, the page dots under it.
            Column(
                Modifier.fillMaxWidth().padding(horizontal = Spacing.x3, vertical = Spacing.x2),
                verticalArrangement = Arrangement.spacedBy(Spacing.x15),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (pager.currentPage == PAGE_SOURCES && state.failure != null) {
                    StatusLine(
                        stringResource(R.string.onboarding_register_failed, apiErrorMessage(state.failure!!)),
                        SentinelIcons.Info,
                        SentinelTheme.colors[Tier.Critical].content,
                    )
                }
                when (pager.currentPage) {
                    PAGE_SOURCES -> PrimaryButton(
                        stringResource(if (state.failure != null) R.string.action_retry else R.string.onboarding_finish),
                        vm::finish,
                        Modifier.fillMaxWidth(),
                        enabled = !state.finishing,
                    )
                    else -> PrimaryButton(stringResource(R.string.action_next), { go(pager.currentPage + 1) }, Modifier.fillMaxWidth())
                }
                if (state.finishing) {
                    Text(stringResource(R.string.onboarding_registering), style = SentinelTheme.type.footnote, color = p.secondaryLabel)
                }
                PageIndicator(PAGES, pager.currentPage, stringResource(R.string.onboarding_step, pager.currentPage + 1, PAGES))
            }
        }
    } }

    sheet?.let { name ->
        val source = ConsentSource.valueOf(name)
        val allowLabel = if (source == ConsentSource.Check) null else R.string.consent_allow
        ConsentSheet(
            source = source,
            allowLabel = allowLabel,
            onAllow = {
                sheet = null
                vm.setSource(source, true)
            },
            onDismiss = { sheet = null },
        )
    }
}

/** A page whose content is vertically centred when it fits and scrolls when it does not (large fonts). */
@Composable
private fun CenteredPage(content: @Composable ColumnScope.() -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).heightIn(min = maxHeight).fillMaxWidth()
                .padding(horizontal = Spacing.x4, vertical = Spacing.x2),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            content = content,
        )
    }
}

@Composable
private fun PageTitle(text: String, modifier: Modifier = Modifier, center: Boolean = false) {
    Text(
        text,
        style = SentinelTheme.type.largeTitle,
        color = SentinelTheme.palette.label,
        textAlign = if (center) TextAlign.Center else TextAlign.Start,
        modifier = modifier.fillMaxWidth().semantics { heading() },
    )
}

@Composable
private fun PageBody(text: String, modifier: Modifier = Modifier, center: Boolean = false) {
    Text(
        text,
        style = SentinelTheme.type.body,
        color = SentinelTheme.palette.secondaryLabel,
        textAlign = if (center) TextAlign.Center else TextAlign.Start,
        modifier = modifier.fillMaxWidth(),
    )
}

@Composable
private fun Promise(icon: ImageVector, title: Int, body: Int) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = Spacing.x1).semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.spacedBy(Spacing.x2),
    ) {
        IconTile(icon)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(stringResource(title), style = SentinelTheme.type.headline, color = SentinelTheme.palette.label)
            Text(stringResource(body), style = SentinelTheme.type.subhead, color = SentinelTheme.palette.secondaryLabel)
        }
    }
}

@Composable
private fun SourcesPage(
    state: OnboardingState,
    onToggle: (ConsentSource, Boolean) -> Unit,
    onInfo: (ConsentSource) -> Unit,
    onFix: (ConsentSource) -> Unit,
    onAllowNotifications: () -> Unit,
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = Spacing.x1)) {
        PageTitle(stringResource(R.string.onboarding_sources_title), modifier = Modifier.padding(horizontal = Spacing.x3))
        Spacer(Modifier.height(Spacing.x1))
        PageBody(stringResource(R.string.onboarding_sources_body), modifier = Modifier.padding(horizontal = Spacing.x3))
        Spacer(Modifier.height(Spacing.x1))
        GroupedSection(Modifier.padding(top = Spacing.x2)) {
            ConsentSource.entries.forEachIndexed { i, source ->
                if (i > 0) GroupedDivider(IconRowDividerInset)
                SourceRow(
                    source = source,
                    status = state.sources.status(source),
                    onToggle = { onToggle(source, it) },
                    onInfo = { onInfo(source) },
                    onFix = { onFix(source) },
                )
            }
        }
        if (!state.sources.postNotifications) {
            GroupedSection(Modifier.padding(top = Spacing.x2), footer = stringResource(R.string.onboarding_alerts_body)) {
                GroupedRow(
                    title = stringResource(R.string.onboarding_alerts_title),
                    icon = SentinelIcons.Alerts,
                    trailing = { PlainButton(stringResource(R.string.consent_allow), onAllowNotifications) },
                )
            }
        }
        Spacer(Modifier.size(Spacing.x2))
    }
}

/** Illustrations drift at a third of the page speed and fade, so pages feel layered while swiping. */
@Composable
private fun Modifier.parallax(pager: androidx.compose.foundation.pager.PagerState, page: Int): Modifier {
    if (rememberReducedMotion()) return this
    return graphicsLayer {
        val offset = (pager.currentPage - page) + pager.currentPageOffsetFraction
        translationX = offset * size.width * 0.35f
        alpha = 1f - kotlin.math.abs(offset).coerceIn(0f, 1f) * 0.6f
    }
}
