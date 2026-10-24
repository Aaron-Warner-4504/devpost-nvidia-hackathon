package ai.enthral.sentinel.ui.onboarding

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ai.enthral.sentinel.R
import ai.enthral.sentinel.domain.ConsentSource
import ai.enthral.sentinel.ui.common.AdaptiveBox
import ai.enthral.sentinel.ui.common.graphViewModel
import ai.enthral.sentinel.ui.common.rememberPostNotificationsRequester
import ai.enthral.sentinel.ui.common.rememberReducedMotion
import ai.enthral.sentinel.ui.common.rememberSystemRequester
import ai.enthral.sentinel.ui.components.ConsentSheet
import ai.enthral.sentinel.ui.components.PrimaryButton
import ai.enthral.sentinel.ui.components.SecondaryButton
import ai.enthral.sentinel.ui.components.SourceRow
import ai.enthral.sentinel.ui.components.apiErrorMessage
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
    val progress by animateFloatAsState((pager.currentPage + 1f) / PAGES, label = "progress")

    AdaptiveBox(Modifier.safeDrawingPadding()) {
        Column(Modifier.fillMaxSize()) {
            Column(Modifier.padding(horizontal = Spacing.gutter, vertical = Spacing.x2), verticalArrangement = Arrangement.spacedBy(Spacing.x1)) {
                Text(
                    stringResource(R.string.onboarding_step, pager.currentPage + 1, PAGES),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            }
            HorizontalPager(pager, Modifier.weight(1f), userScrollEnabled = true) { page ->
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Spacing.gutter),
                    verticalArrangement = Arrangement.spacedBy(Spacing.x2),
                ) {
                    when (page) {
                        0 -> WelcomePage()
                        1 -> PrivacyPage()
                        PAGE_LANGUAGE -> LanguagePage(vm.language(), vm::setLanguage)
                        else -> SourcesPage(
                            state = state,
                            onToggle = { source, on ->
                                if (on) sheet = source.name else vm.setSource(source, false)
                            },
                            onInfo = { sheet = it.name },
                            onFix = vm::fixSource,
                            onAllowNotifications = askPostNotifications,
                        )
                    }
                    Spacer(Modifier.size(Spacing.x2))
                }
            }
            // Controls
            Column(Modifier.padding(horizontal = Spacing.gutter, vertical = Spacing.x2), verticalArrangement = Arrangement.spacedBy(Spacing.x1)) {
                if (pager.currentPage == PAGE_SOURCES && state.failure != null) {
                    Text(
                        stringResource(R.string.onboarding_register_failed, apiErrorMessage(state.failure!!)),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
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
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    if (pager.currentPage > 0) {
                        TextButton({ go(pager.currentPage - 1) }, Modifier.heightIn(min = Spacing.minTouchTarget)) {
                            Text(stringResource(R.string.action_back))
                        }
                    } else {
                        Spacer(Modifier.size(1.dp))
                    }
                    // Language is the only step that is safe to skip (the device language stays in effect).
                    if (pager.currentPage == PAGE_LANGUAGE) {
                        TextButton({ go(PAGE_SOURCES) }, Modifier.heightIn(min = Spacing.minTouchTarget)) {
                            Text(stringResource(R.string.action_skip))
                        }
                    }
                }
                if (state.finishing) {
                    Text(stringResource(R.string.onboarding_registering), style = MaterialTheme.typography.bodySmall, modifier = Modifier.align(Alignment.CenterHorizontally))
                }
            }
        }
    }

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

@Composable
private fun WelcomePage() {
    Column(Modifier.fillMaxWidth().padding(top = Spacing.x4), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Spacing.x2)) {
        Icon(painterResource(R.drawable.ic_shield), null, Modifier.size(96.dp), tint = MaterialTheme.colorScheme.primary)
        Text(stringResource(R.string.onboarding_welcome_title), style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center, modifier = Modifier.semantics { heading() })
        Text(stringResource(R.string.onboarding_welcome_body), style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun PrivacyPage() {
    Text(stringResource(R.string.onboarding_privacy_title), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(top = Spacing.x2).semantics { heading() })
    PromiseCard(Icons.Filled.Lock, R.string.promise_1_title, R.string.promise_1_body)
    PromiseCard(R.drawable.ic_shield, R.string.promise_2_title, R.string.promise_2_body)
    PromiseCard(Icons.Filled.Delete, R.string.promise_3_title, R.string.promise_3_body)
}


@Composable
private fun PromiseCard(icon: Any, title: Int, body: Int) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Row(Modifier.padding(Spacing.x2), horizontalArrangement = Arrangement.spacedBy(Spacing.x2), verticalAlignment = Alignment.Top) {
            when (icon) {
                is androidx.compose.ui.graphics.vector.ImageVector -> Icon(icon, null, Modifier.size(32.dp), tint = MaterialTheme.colorScheme.primary)
                is Int -> Icon(painterResource(icon), null, Modifier.size(32.dp), tint = MaterialTheme.colorScheme.primary)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.x05)) {
                Text(stringResource(title), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun LanguagePage(current: String, onSelect: (String) -> Unit) {
    Text(stringResource(R.string.onboarding_language_title), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(top = Spacing.x2).semantics { heading() })
    Text(stringResource(R.string.onboarding_language_body), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(Spacing.x1)) {
        LanguageOption("English", "en", current == "en", onSelect)
        LanguageOption("हिन्दी", "hi", current == "hi", onSelect)
    }
}

@Composable
fun LanguageOption(label: String, tag: String, selected: Boolean, onSelect: (String) -> Unit) {
    Surface(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).selectable(selected = selected, role = Role.RadioButton, onClick = { onSelect(tag) }),
        shape = MaterialTheme.shapes.medium,
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLowest,
        border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(Modifier.padding(horizontal = Spacing.x2), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.x1)) {
            RadioButton(selected = selected, onClick = null)
            Text(label, style = MaterialTheme.typography.titleMedium)
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
    Text(stringResource(R.string.onboarding_sources_title), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(top = Spacing.x2).semantics { heading() })
    Text(stringResource(R.string.onboarding_sources_body), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.padding(horizontal = Spacing.x2)) {
            ConsentSource.entries.forEach { source ->
                SourceRow(
                    source = source,
                    status = state.sources.status(source),
                    onToggle = { onToggle(source, it) },
                    onInfo = { onInfo(source) },
                    onFix = { onFix(source) },
                )
            }
        }
    }
    if (!state.sources.postNotifications) {
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
            Column(Modifier.padding(Spacing.x2), verticalArrangement = Arrangement.spacedBy(Spacing.x1)) {
                Text(stringResource(R.string.onboarding_alerts_title), style = MaterialTheme.typography.titleSmall)
                Text(stringResource(R.string.onboarding_alerts_body), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                SecondaryButton(stringResource(R.string.onboarding_alerts_allow), onAllowNotifications)
            }
        }
    }
}
