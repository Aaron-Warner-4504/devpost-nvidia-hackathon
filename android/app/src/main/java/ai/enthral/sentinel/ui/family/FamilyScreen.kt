package ai.enthral.sentinel.ui.family

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ai.enthral.sentinel.R
import ai.enthral.sentinel.domain.Guardian
import ai.enthral.sentinel.domain.Protectee
import ai.enthral.sentinel.ui.common.AdaptiveBox
import ai.enthral.sentinel.ui.common.graphViewModel
import ai.enthral.sentinel.ui.components.CodeEntry
import ai.enthral.sentinel.ui.components.NoteCard
import ai.enthral.sentinel.ui.components.PrimaryButton
import ai.enthral.sentinel.ui.components.SecondaryButton
import ai.enthral.sentinel.ui.components.SectionHeader
import ai.enthral.sentinel.ui.components.formatTimestamp
import ai.enthral.sentinel.ui.theme.Spacing
import kotlinx.coroutines.delay

@Composable
fun FamilyScreen() {
    val vm = graphViewModel { FamilyViewModel.create(it) }
    val state by vm.state.collectAsStateWithLifecycle()
    // Countdown is driven from the UI: it only ticks while this screen is visible.
    LaunchedEffect(state.invite) {
        while (state.invite != null && !state.inviteExpired) {
            delay(1000)
            vm.tick()
        }
    }
    FamilyContent(
        state = state,
        onConsent = vm::setConsent,
        onCreateInvite = vm::createInvite,
        onCode = vm::onCodeChange,
        onName = vm::onNameChange,
        onAccept = vm::accept,
        onUnlink = vm::unlink,
        onRetryLinks = vm::loadLinks,
        onDismissUnlinkError = vm::dismissUnlinkError,
    )
}

@Composable
fun familyErrorMessage(e: FamilyError): String = stringResource(
    when (e) {
        FamilyError.Offline -> R.string.error_network
        FamilyError.InvalidCode -> R.string.family_error_invalid_code
        FamilyError.RateLimited -> R.string.family_error_rate_limited
        FamilyError.GuardianLimit -> R.string.family_error_guardian_limit
        FamilyError.InvalidInput -> R.string.family_error_invalid_input
        FamilyError.Server -> R.string.family_error_server
    },
)

@Composable
fun FamilyContent(
    state: FamilyUiState,
    onConsent: (Boolean) -> Unit,
    onCreateInvite: () -> Unit,
    onCode: (String) -> Unit,
    onName: (String) -> Unit,
    onAccept: () -> Unit,
    onUnlink: (String) -> Unit,
    onRetryLinks: () -> Unit,
    onDismissUnlinkError: () -> Unit,
) {
    var confirmUnlink by rememberSaveable { mutableStateOf<String?>(null) }
    AdaptiveBox(Modifier.statusBarsPadding()) {
        LazyColumn(
            contentPadding = PaddingValues(horizontal = Spacing.gutter, vertical = Spacing.x2),
            verticalArrangement = Arrangement.spacedBy(Spacing.x2),
        ) {
            item {
                Text(stringResource(R.string.nav_family), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
            }
            item { Text(stringResource(R.string.family_intro), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            item { ProtectMeCard(state, onConsent, onCreateInvite, onUnlink = { confirmUnlink = it }) }
            item { ProtectSomeoneCard(state, onCode, onName, onAccept, onUnlink = { confirmUnlink = it }) }
            if (state.links is LinksState.Failed) {
                item {
                    NoteCard(stringResource(R.string.family_links_failed), iconRes = R.drawable.ic_offline)
                    SecondaryButton(stringResource(R.string.action_retry), onRetryLinks, Modifier.padding(top = Spacing.x1))
                }
            }
        }
    }
    confirmUnlink?.let { id ->
        val isGuardian = (state.links as? LinksState.Ready)?.links?.guardians?.any { it.id == id } == true
        AlertDialog(
            onDismissRequest = { confirmUnlink = null },
            title = { Text(stringResource(if (isGuardian) R.string.family_remove_title else R.string.family_stop_title)) },
            text = { Text(stringResource(if (isGuardian) R.string.family_remove_body else R.string.family_stop_body)) },
            confirmButton = {
                TextButton({ confirmUnlink = null; onUnlink(id) }) {
                    Text(stringResource(if (isGuardian) R.string.family_remove else R.string.family_stop))
                }
            },
            dismissButton = { TextButton({ confirmUnlink = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
    state.unlinkError?.let { err ->
        AlertDialog(
            onDismissRequest = onDismissUnlinkError,
            title = { Text(stringResource(R.string.error_title)) },
            text = { Text(familyErrorMessage(err)) },
            confirmButton = { TextButton(onDismissUnlinkError) { Text(stringResource(R.string.action_got_it)) } },
        )
    }
}

@Composable
private fun SectionCard(title: String, body: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest)) {
        Column(Modifier.padding(Spacing.x2), verticalArrangement = Arrangement.spacedBy(Spacing.x2)) {
            Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            content()
        }
    }
}

@Composable
private fun ProtectMeCard(state: FamilyUiState, onConsent: (Boolean) -> Unit, onCreate: () -> Unit, onUnlink: (String) -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    SectionCard(stringResource(R.string.family_protect_me_title), stringResource(R.string.family_protect_me_body)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.x2), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.x05)) {
                Text(stringResource(R.string.family_consent_title), style = MaterialTheme.typography.titleSmall)
                Text(stringResource(R.string.family_consent_body), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            val label = stringResource(R.string.family_consent_title)
            Switch(state.consent, onConsent, modifier = Modifier.semantics { contentDescription = label })
        }
        val invite = state.invite
        if (invite != null) {
            val spoken = invite.code.toCharArray().joinToString(" ")
            Text(
                invite.code,
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = spoken },
                style = MaterialTheme.typography.displaySmall.copy(fontFamily = FontFamily.Monospace, letterSpacing = 6.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold),
                textAlign = TextAlign.Center,
            )
            Text(
                if (state.inviteExpired) stringResource(R.string.family_code_expired)
                else stringResource(R.string.family_code_expires, formatCountdown(state.remainingSeconds)),
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.titleSmall,
                color = if (state.inviteExpired) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            )
            if (!state.inviteExpired) {
                val shareText = stringResource(R.string.family_share_text, invite.code)
                val chooser = stringResource(R.string.family_share_chooser)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.x1)) {
                    SecondaryButton(stringResource(R.string.family_copy), { clipboard.setText(AnnotatedString(invite.code)) }, Modifier.weight(1f), iconRes = R.drawable.ic_copy)
                    PrimaryButton(
                        stringResource(R.string.family_share),
                        {
                            val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, shareText)
                            context.startActivity(Intent.createChooser(send, chooser))
                        },
                        Modifier.weight(1f),
                    )
                }
            }
            SecondaryButton(stringResource(R.string.family_regenerate), onCreate, Modifier.fillMaxWidth(), enabled = state.consent && !state.creating)
        } else {
            PrimaryButton(stringResource(R.string.family_create_code), onCreate, Modifier.fillMaxWidth(), enabled = state.consent && !state.creating)
            if (!state.consent) {
                Text(stringResource(R.string.family_consent_required), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        state.inviteError?.let { Text(familyErrorMessage(it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }

        SectionHeader(stringResource(R.string.family_my_guardians))
        when (val l = state.links) {
            is LinksState.Ready ->
                if (l.links.guardians.isEmpty()) {
                    Text(stringResource(R.string.family_no_guardians), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    l.links.guardians.forEach { GuardianRow(it, state.workingLinkId == it.id) { onUnlink(it.id) } }
                }
            LinksState.Loading -> Text(stringResource(R.string.loading_cd), style = MaterialTheme.typography.bodyMedium)
            LinksState.Failed -> Unit
        }
    }
}

fun formatCountdown(seconds: Long): String = "%02d:%02d".format(seconds / 60, seconds % 60)

@Composable
private fun GuardianRow(g: Guardian, working: Boolean, onRemove: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = Spacing.minTouchTarget), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(g.name, style = MaterialTheme.typography.titleSmall)
            Text(stringResource(R.string.family_linked_on, formatTimestamp(g.linkedAt)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        TextButton(onRemove, enabled = !working, modifier = Modifier.heightIn(min = Spacing.minTouchTarget)) { Text(stringResource(R.string.family_remove)) }
    }
}

@Composable
private fun ProtectSomeoneCard(
    state: FamilyUiState,
    onCode: (String) -> Unit,
    onName: (String) -> Unit,
    onAccept: () -> Unit,
    onUnlink: (String) -> Unit,
) {
    SectionCard(stringResource(R.string.family_protect_someone_title), stringResource(R.string.family_protect_someone_body)) {
        CodeEntry(state.code, onCode, enabled = !state.accepting, isError = state.acceptError == FamilyError.InvalidCode)
        OutlinedTextField(
            value = state.name,
            onValueChange = onName,
            modifier = Modifier.fillMaxWidth(),
            enabled = !state.accepting,
            singleLine = true,
            label = { Text(stringResource(R.string.family_name_label)) },
            supportingText = { Text(stringResource(R.string.family_name_hint)) },
        )
        Text(stringResource(R.string.family_push_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        PrimaryButton(stringResource(R.string.family_link), onAccept, Modifier.fillMaxWidth(), enabled = state.canAccept)
        state.acceptError?.let { Text(familyErrorMessage(it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
        if (state.accepted) {
            NoteCard(stringResource(R.string.family_linked_success), iconRes = R.drawable.ic_shield)
        }
        SectionHeader(stringResource(R.string.family_people_i_protect))
        when (val l = state.links) {
            is LinksState.Ready ->
                if (l.links.protecting.isEmpty()) {
                    Text(stringResource(R.string.family_no_protecting), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    l.links.protecting.forEachIndexed { i, p -> ProtecteeRow(i + 1, p, state.workingLinkId == p.id) { onUnlink(p.id) } }
                }
            else -> Unit
        }
    }
}

@Composable
private fun ProtecteeRow(index: Int, p: Protectee, working: Boolean, onStop: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = Spacing.minTouchTarget), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.family_person, index), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(R.string.family_linked_on, formatTimestamp(p.linkedAt)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        TextButton(onStop, enabled = !working, modifier = Modifier.heightIn(min = Spacing.minTouchTarget)) { Text(stringResource(R.string.family_stop)) }
    }
}
