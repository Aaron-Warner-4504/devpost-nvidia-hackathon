package ai.enthral.sentinel.ui.settings

import android.os.Build
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ai.enthral.sentinel.BuildConfig
import ai.enthral.sentinel.R
import ai.enthral.sentinel.data.notify.NotificationChannels
import ai.enthral.sentinel.domain.ConsentSource
import ai.enthral.sentinel.ui.common.AdaptiveBox
import ai.enthral.sentinel.ui.common.graphViewModel
import ai.enthral.sentinel.ui.common.openAppNotificationSettings
import ai.enthral.sentinel.ui.common.openChannelSettings
import ai.enthral.sentinel.ui.common.rememberPostNotificationsRequester
import ai.enthral.sentinel.ui.common.rememberSystemRequester
import ai.enthral.sentinel.ui.components.ConsentSheet
import ai.enthral.sentinel.ui.components.PrimaryButton
import ai.enthral.sentinel.ui.components.SecondaryButton
import ai.enthral.sentinel.ui.components.SectionHeader
import ai.enthral.sentinel.ui.components.SourceRow
import ai.enthral.sentinel.ui.components.apiErrorMessage
import ai.enthral.sentinel.ui.onboarding.LanguageOption
import ai.enthral.sentinel.ui.theme.Spacing

@Composable
fun SettingsScreen(onOpenDemo: () -> Unit) {
    val vm = graphViewModel { SettingsViewModel.create(it) }
    val state by vm.state.collectAsStateWithLifecycle()
    val requester = rememberSystemRequester(vm::onCallPermissionResult)
    val askPost = rememberPostNotificationsRequester { vm.onPostNotificationsResult() }
    LaunchedEffect(vm) {
        vm.events.flow.collect { e ->
            when (e) {
                is SettingsEvent.Request -> requester(e.request)
                SettingsEvent.RequestPostNotifications -> askPost()
            }
        }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.onResume() }
    SettingsContent(
        state = state,
        onSource = vm::setSource,
        onFix = vm::fixSource,
        onPaused = vm::setPaused,
        onLanguage = vm::setLanguage,
        onDynamicColor = vm::setDynamicColor,
        onAllowNotifications = vm::requestPostNotifications,
        onDelete = vm::deleteEverything,
        onDismissDeleteFailure = vm::dismissDeleteFailure,
        onOpenDemo = onOpenDemo,
    )
}

@Composable
fun SettingsContent(
    state: SettingsUiState,
    onSource: (ConsentSource, Boolean) -> Unit,
    onFix: (ConsentSource) -> Unit,
    onPaused: (Boolean) -> Unit,
    onLanguage: (String) -> Unit,
    onDynamicColor: (Boolean) -> Unit,
    onAllowNotifications: () -> Unit,
    onDelete: () -> Unit,
    onDismissDeleteFailure: () -> Unit,
    onOpenDemo: () -> Unit,
) {
    val context = LocalContext.current
    var sheet by rememberSaveable { mutableStateOf<String?>(null) }
    var showDelete by rememberSaveable { mutableStateOf(false) }
    var showPrivacy by rememberSaveable { mutableStateOf(false) }

    AdaptiveBox(Modifier.statusBarsPadding()) {
        LazyColumn(
            contentPadding = PaddingValues(horizontal = Spacing.gutter, vertical = Spacing.x2),
            verticalArrangement = Arrangement.spacedBy(Spacing.x1),
        ) {
            item { Text(stringResource(R.string.nav_settings), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() }) }

            // --- Sources ---------------------------------------------------------------------------------
            item { SectionHeader(stringResource(R.string.settings_sources)) }
            item {
                Panel {
                    ConsentSource.entries.forEach { source ->
                        SourceRow(
                            source = source,
                            status = state.sources.status(source),
                            onToggle = { on -> if (on) sheet = source.name else onSource(source, false) },
                            onInfo = { sheet = source.name },
                            onFix = { onFix(source) },
                            fixLabel = R.string.source_fix,
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                    ToggleRow(R.string.settings_pause_title, R.string.settings_pause_body, state.paused, onPaused)
                }
            }
            if (state.consentSyncFailed) {
                item { Text(stringResource(R.string.settings_consent_sync_failed), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
            }

            // --- Language --------------------------------------------------------------------------------
            item { SectionHeader(stringResource(R.string.settings_language)) }
            item {
                Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(Spacing.x1)) {
                    LanguageOption("English", "en", state.language == "en", onLanguage)
                    LanguageOption("हिन्दी", "hi", state.language == "hi", onLanguage)
                }
            }

            // --- Notifications ---------------------------------------------------------------------------
            item { SectionHeader(stringResource(R.string.settings_notifications)) }
            item {
                Panel {
                    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.x2)) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.settings_alerts_permission), style = MaterialTheme.typography.titleSmall)
                            Text(
                                stringResource(if (state.sources.postNotifications) R.string.settings_alerts_allowed else R.string.settings_alerts_blocked),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (!state.sources.postNotifications) {
                            SecondaryButton(stringResource(R.string.settings_alerts_allow), onAllowNotifications)
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    ChannelRow(R.string.channel_critical_name, R.string.channel_critical_desc) { context.openChannelSettings(NotificationChannels.CRITICAL) }
                    ChannelRow(R.string.channel_high_name, R.string.channel_high_desc) { context.openChannelSettings(NotificationChannels.HIGH) }
                    ChannelRow(R.string.channel_updates_name, R.string.channel_updates_desc) { context.openChannelSettings(NotificationChannels.UPDATES) }
                    TextButton({ context.openAppNotificationSettings() }, Modifier.heightIn(min = Spacing.minTouchTarget)) {
                        Text(stringResource(R.string.settings_open_system_notifications))
                    }
                }
            }

            // --- Appearance (Android 12+) ----------------------------------------------------------------
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                item { SectionHeader(stringResource(R.string.settings_appearance)) }
                item { Panel { ToggleRow(R.string.settings_dynamic_title, R.string.settings_dynamic_body, state.dynamicColor, onDynamicColor) } }
            }

            // --- Demo -------------------------------------------------------------------------------------
            if (state.demoEnabled) {
                item { SectionHeader(stringResource(R.string.settings_demo)) }
                item {
                    Panel {
                        Text(stringResource(R.string.settings_demo_body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        SecondaryButton(stringResource(R.string.settings_demo_open), onOpenDemo, Modifier.fillMaxWidth())
                    }
                }
            }

            // --- Data & privacy --------------------------------------------------------------------------
            item { SectionHeader(stringResource(R.string.settings_privacy)) }
            item {
                Panel {
                    Text(stringResource(R.string.settings_privacy_summary), style = MaterialTheme.typography.bodyMedium)
                    TextButton({ showPrivacy = !showPrivacy }, Modifier.heightIn(min = Spacing.minTouchTarget)) {
                        Text(stringResource(if (showPrivacy) R.string.settings_privacy_hide else R.string.settings_privacy_show))
                    }
                    if (showPrivacy) {
                        Column(verticalArrangement = Arrangement.spacedBy(Spacing.x1)) {
                            listOf(R.string.privacy_leaves_title, R.string.privacy_leaves_body, R.string.privacy_stays_title, R.string.privacy_stays_body, R.string.privacy_retention_title, R.string.privacy_retention_body)
                                .chunked(2).forEach { (t, b) ->
                                    Text(stringResource(t), style = MaterialTheme.typography.titleSmall)
                                    Text(stringResource(b), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                        }
                    }
                    Button(
                        onClick = { showDelete = true },
                        modifier = Modifier.fillMaxWidth().heightIn(min = Spacing.minTouchTarget),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError),
                        shape = MaterialTheme.shapes.medium,
                    ) { Text(stringResource(R.string.settings_delete), style = MaterialTheme.typography.labelLarge) }
                }
            }

            // --- About -----------------------------------------------------------------------------------
            item { SectionHeader(stringResource(R.string.settings_about)) }
            item {
                Panel {
                    AboutRow(R.string.about_version, "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                    state.debugInfo?.let {
                        AboutRow(R.string.about_build_type, it.buildType)
                        AboutRow(R.string.about_base_url, it.baseUrl)
                    }
                }
            }
        }
    }

    sheet?.let { name ->
        val source = ConsentSource.valueOf(name)
        ConsentSheet(
            source = source,
            allowLabel = if (source == ConsentSource.Check) null else R.string.consent_allow,
            onAllow = { sheet = null; onSource(source, true) },
            onDismiss = { sheet = null },
        )
    }
    if (showDelete) {
        DeleteDialog(
            deleting = state.deleting,
            failure = state.deleteFailure,
            onConfirm = onDelete,
            onDismiss = { showDelete = false; onDismissDeleteFailure() },
        )
    }
}


@Composable
private fun Panel(content: @Composable () -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) { Column(Modifier.padding(horizontal = Spacing.x2, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(Spacing.x1)) { content() } }
}

@Composable
private fun ToggleRow(title: Int, body: Int, checked: Boolean, onChange: (Boolean) -> Unit) {
    val label = stringResource(title)
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.x2)) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.titleSmall)
            Text(stringResource(body), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked, onChange, modifier = Modifier.semantics { contentDescription = label })
    }
}

@Composable
private fun ChannelRow(name: Int, desc: Int, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().heightIn(min = Spacing.minTouchTarget).clickable(onClick = onClick).padding(vertical = Spacing.x1)) {
        Text(stringResource(name), style = MaterialTheme.typography.titleSmall)
        Text(stringResource(desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun AboutRow(label: Int, value: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = Spacing.x05)) {
        Text(stringResource(label), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun DeleteDialog(
    deleting: Boolean,
    failure: ai.enthral.sentinel.data.api.ApiResult<Nothing>?,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    var typed by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = { if (!deleting) onDismiss() },
        title = { Text(stringResource(R.string.delete_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.x1)) {
                Text(stringResource(R.string.delete_body))
                OutlinedTextField(
                    value = typed,
                    onValueChange = { typed = it },
                    enabled = !deleting,
                    singleLine = true,
                    label = { Text(stringResource(R.string.delete_type_label, DELETE_CONFIRM_WORD)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (failure != null) {
                    Text(stringResource(R.string.delete_failed, apiErrorMessage(failure)), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = isDeleteConfirmed(typed) && !deleting) {
                Text(stringResource(if (deleting) R.string.delete_working else R.string.delete_confirm), color = if (isDeleteConfirmed(typed)) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        dismissButton = { TextButton(onDismiss, enabled = !deleting) { Text(stringResource(R.string.action_cancel)) } },
    )
}
