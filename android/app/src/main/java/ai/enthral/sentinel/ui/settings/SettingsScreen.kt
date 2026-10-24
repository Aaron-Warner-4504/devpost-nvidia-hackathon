package ai.enthral.sentinel.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ai.enthral.sentinel.BuildConfig
import ai.enthral.sentinel.R
import ai.enthral.sentinel.data.notify.NotificationChannels
import ai.enthral.sentinel.domain.ConsentSource
import ai.enthral.sentinel.domain.Tier
import ai.enthral.sentinel.ui.common.graphViewModel
import ai.enthral.sentinel.ui.common.openAppNotificationSettings
import ai.enthral.sentinel.ui.common.openChannelSettings
import ai.enthral.sentinel.ui.common.rememberPostNotificationsRequester
import ai.enthral.sentinel.ui.common.rememberSystemRequester
import ai.enthral.sentinel.ui.components.ConfirmSheet
import ai.enthral.sentinel.ui.components.ConsentSheet
import ai.enthral.sentinel.ui.components.GroupedDivider
import ai.enthral.sentinel.ui.components.GroupedRow
import ai.enthral.sentinel.ui.components.GroupedSection
import ai.enthral.sentinel.ui.components.IconRowDividerInset
import ai.enthral.sentinel.ui.components.LargeTitleList
import ai.enthral.sentinel.ui.components.PlainButton
import ai.enthral.sentinel.ui.components.PrimaryButton
import ai.enthral.sentinel.ui.components.SentinelIcons
import ai.enthral.sentinel.ui.components.SentinelSheet
import ai.enthral.sentinel.ui.components.SentinelTextField
import ai.enthral.sentinel.ui.components.SourceRow
import ai.enthral.sentinel.ui.components.StatusLine
import ai.enthral.sentinel.ui.components.ToggleRow
import ai.enthral.sentinel.ui.components.ValueRow
import ai.enthral.sentinel.ui.components.apiErrorMessage
import ai.enthral.sentinel.ui.components.rememberHaptics
import ai.enthral.sentinel.ui.theme.SentinelTheme
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
        onTrueBlack = vm::setTrueBlack,
        onAllowNotifications = vm::requestPostNotifications,
        onDelete = vm::deleteEverything,
        onDismissDeleteFailure = vm::dismissDeleteFailure,
        onOpenDemo = onOpenDemo,
    )
}

/** Languages offered, shown in their own script. */
val Languages = listOf("en" to "English", "hi" to "हिन्दी")

@Composable
fun SettingsContent(
    state: SettingsUiState,
    onSource: (ConsentSource, Boolean) -> Unit,
    onFix: (ConsentSource) -> Unit,
    onPaused: (Boolean) -> Unit,
    onLanguage: (String) -> Unit,
    onTrueBlack: (Boolean) -> Unit,
    onAllowNotifications: () -> Unit,
    onDelete: () -> Unit,
    onDismissDeleteFailure: () -> Unit,
    onOpenDemo: () -> Unit,
) {
    val context = LocalContext.current
    var sheet by rememberSaveable { mutableStateOf<String?>(null) }
    var showDelete by rememberSaveable { mutableStateOf(false) }
    var showPrivacy by rememberSaveable { mutableStateOf(false) }

    LargeTitleList(title = stringResource(R.string.nav_settings)) {
        item(key = "sources") {
            GroupedSection(header = stringResource(R.string.settings_sources)) {
                ConsentSource.entries.forEach { source ->
                    SourceRow(
                        source = source,
                        status = state.sources.status(source),
                        onToggle = { on -> if (on) sheet = source.name else onSource(source, false) },
                        onInfo = { sheet = source.name },
                        onFix = { onFix(source) },
                        fixLabel = R.string.source_fix,
                    )
                    GroupedDivider(IconRowDividerInset)
                }
                ToggleRow(
                    title = stringResource(R.string.settings_pause_title),
                    subtitle = stringResource(R.string.settings_pause_body),
                    checked = state.paused,
                    onCheckedChange = onPaused,
                    icon = SentinelIcons.Pause,
                )
            }
        }
        if (state.consentSyncFailed) {
            item(key = "sync-failed") {
                StatusLine(
                    stringResource(R.string.settings_consent_sync_failed),
                    SentinelIcons.CloudOff,
                    SentinelTheme.colors[Tier.Caution].content,
                    Modifier.padding(horizontal = Spacing.gutter, vertical = Spacing.x1),
                )
            }
        }

        item(key = "language") {
            GroupedSection(header = stringResource(R.string.settings_language)) {
                Column(Modifier.selectableGroup()) {
                    Languages.forEachIndexed { i, (tag, name) ->
                        if (i > 0) GroupedDivider(IconRowDividerInset)
                        LanguageRow(name, state.language == tag) { onLanguage(tag) }
                    }
                }
            }
        }

        item(key = "notifications") {
            GroupedSection(header = stringResource(R.string.settings_notifications)) {
                GroupedRow(
                    title = stringResource(R.string.settings_alerts_permission),
                    subtitle = stringResource(if (state.sources.postNotifications) R.string.settings_alerts_allowed else R.string.settings_alerts_blocked),
                    icon = SentinelIcons.Alerts,
                    trailing = if (state.sources.postNotifications) null else {
                        { PlainButton(stringResource(R.string.settings_alerts_allow), onAllowNotifications) }
                    },
                )
                GroupedDivider(IconRowDividerInset)
                ChannelRow(R.string.channel_critical_name, R.string.channel_critical_desc) { context.openChannelSettings(NotificationChannels.CRITICAL) }
                GroupedDivider(IconRowDividerInset)
                ChannelRow(R.string.channel_high_name, R.string.channel_high_desc) { context.openChannelSettings(NotificationChannels.HIGH) }
                GroupedDivider(IconRowDividerInset)
                ChannelRow(R.string.channel_updates_name, R.string.channel_updates_desc) { context.openChannelSettings(NotificationChannels.UPDATES) }
                GroupedDivider(IconRowDividerInset)
                GroupedRow(
                    title = stringResource(R.string.settings_open_system_notifications),
                    icon = SentinelIcons.ExternalLink,
                    onClick = { context.openAppNotificationSettings() },
                    chevron = true,
                )
            }
        }

        item(key = "appearance") {
            GroupedSection(header = stringResource(R.string.settings_appearance)) {
                ToggleRow(
                    title = stringResource(R.string.settings_true_black_title),
                    subtitle = stringResource(R.string.settings_true_black_body),
                    checked = state.trueBlack,
                    onCheckedChange = onTrueBlack,
                    icon = SentinelIcons.Moon,
                )
            }
        }

        if (state.demoEnabled) {
            item(key = "demo") {
                GroupedSection(header = stringResource(R.string.settings_demo), footer = stringResource(R.string.settings_demo_body)) {
                    GroupedRow(stringResource(R.string.settings_demo_open), icon = SentinelIcons.Sparkles, onClick = onOpenDemo, chevron = true)
                }
            }
        }

        item(key = "privacy") {
            GroupedSection(header = stringResource(R.string.settings_privacy), footer = stringResource(R.string.settings_privacy_summary)) {
                GroupedRow(stringResource(R.string.settings_privacy_show), icon = SentinelIcons.Lock, onClick = { showPrivacy = true }, chevron = true)
            }
        }
        item(key = "delete") {
            GroupedSection(Modifier.padding(top = Spacing.x3)) {
                GroupedRow(
                    stringResource(R.string.settings_delete),
                    icon = SentinelIcons.Trash,
                    titleColor = SentinelTheme.colors[Tier.Critical].content,
                    onClick = { showDelete = true },
                )
            }
        }

        item(key = "about") {
            GroupedSection(header = stringResource(R.string.settings_about)) {
                ValueRow(stringResource(R.string.about_version), "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})", icon = SentinelIcons.Info)
                state.debugInfo?.let {
                    GroupedDivider(IconRowDividerInset)
                    GroupedRow(stringResource(R.string.about_build_type), subtitle = it.buildType, icon = SentinelIcons.Settings)
                    GroupedDivider(IconRowDividerInset)
                    GroupedRow(stringResource(R.string.about_base_url), subtitle = it.baseUrl, icon = SentinelIcons.Globe)
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
    if (showPrivacy) PrivacySheet(onDismiss = { showPrivacy = false })
    if (showDelete) {
        DeleteSheet(
            deleting = state.deleting,
            failure = state.deleteFailure,
            onConfirm = onDelete,
            onDismiss = { showDelete = false; onDismissDeleteFailure() },
        )
    }
}

@Composable
private fun LanguageRow(name: String, selected: Boolean, onSelect: () -> Unit) {
    val haptics = rememberHaptics()
    GroupedRow(
        title = name,
        icon = SentinelIcons.Globe,
        modifier = Modifier.selectable(selected = selected, role = Role.RadioButton) { if (!selected) haptics.tick(); onSelect() },
        trailing = {
            if (selected) Icon(SentinelIcons.Checkmark, null, tint = SentinelTheme.palette.accent, modifier = Modifier.size(20.dp))
        },
    )
}

@Composable
private fun ChannelRow(name: Int, desc: Int, onClick: () -> Unit) {
    GroupedRow(
        title = stringResource(name),
        subtitle = stringResource(desc),
        icon = SentinelIcons.Alerts,
        onClick = onClick,
        chevron = true,
    )
}

@Composable
private fun PrivacySheet(onDismiss: () -> Unit) {
    val p = SentinelTheme.palette
    SentinelSheet(onDismiss) {
        Text(stringResource(R.string.settings_privacy), style = SentinelTheme.type.title2, modifier = Modifier.semantics { heading() })
        listOf(
            Triple(SentinelIcons.Share, R.string.privacy_leaves_title, R.string.privacy_leaves_body),
            Triple(SentinelIcons.Lock, R.string.privacy_stays_title, R.string.privacy_stays_body),
            Triple(SentinelIcons.Clock, R.string.privacy_retention_title, R.string.privacy_retention_body),
        ).forEach { (icon, t, b) ->
            androidx.compose.foundation.layout.Row(
                Modifier.fillMaxWidth().padding(top = Spacing.x1),
                horizontalArrangement = Arrangement.spacedBy(Spacing.x15),
            ) {
                ai.enthral.sentinel.ui.components.IconTile(icon)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(stringResource(t), style = SentinelTheme.type.headline, modifier = Modifier.semantics { heading() })
                    Text(stringResource(b), style = SentinelTheme.type.callout, color = p.secondaryLabel)
                }
            }
        }
        PrimaryButton(stringResource(R.string.action_got_it), onDismiss, Modifier.fillMaxWidth().padding(top = Spacing.x2))
    }
}

@Composable
private fun DeleteSheet(
    deleting: Boolean,
    failure: ai.enthral.sentinel.data.api.ApiResult<Nothing>?,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    var typed by rememberSaveable { mutableStateOf("") }
    ConfirmSheet(
        title = stringResource(R.string.delete_title),
        body = stringResource(R.string.delete_body),
        confirmLabel = stringResource(if (deleting) R.string.delete_working else R.string.delete_confirm),
        onConfirm = onConfirm,
        onDismiss = onDismiss,
        confirmEnabled = isDeleteConfirmed(typed) && !deleting,
        dismissEnabled = !deleting,
    ) {
        SentinelTextField(
            value = typed,
            onValueChange = { typed = it },
            label = stringResource(R.string.delete_type_label, DELETE_CONFIRM_WORD),
            enabled = !deleting,
            singleLine = true,
            container = SentinelTheme.palette.elevatedSecondary,
            modifier = Modifier.heightIn(min = 56.dp),
        )
        if (failure != null) {
            StatusLine(
                stringResource(R.string.delete_failed, apiErrorMessage(failure)),
                SentinelIcons.Info,
                SentinelTheme.colors[Tier.Critical].content,
            )
        }
    }
}
