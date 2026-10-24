package ai.enthral.sentinel.ui.family

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
import ai.enthral.sentinel.domain.Guardian
import ai.enthral.sentinel.domain.Protectee
import ai.enthral.sentinel.domain.Tier
import ai.enthral.sentinel.ui.common.graphViewModel
import ai.enthral.sentinel.ui.components.CodeDisplay
import ai.enthral.sentinel.ui.components.CodeEntry
import ai.enthral.sentinel.ui.components.ConfirmSheet
import ai.enthral.sentinel.ui.components.CountdownRing
import ai.enthral.sentinel.ui.components.FamilyIllustration
import ai.enthral.sentinel.ui.components.GroupedDivider
import ai.enthral.sentinel.ui.components.GroupedFooter
import ai.enthral.sentinel.ui.components.GroupedHeader
import ai.enthral.sentinel.ui.components.GroupedRow
import ai.enthral.sentinel.ui.components.GroupedSection
import ai.enthral.sentinel.ui.components.IconRowDividerInset
import ai.enthral.sentinel.ui.components.LargeTitleList
import ai.enthral.sentinel.ui.components.NoteCard
import ai.enthral.sentinel.ui.components.PlainButton
import ai.enthral.sentinel.ui.components.PrimaryButton
import ai.enthral.sentinel.ui.components.SecondaryButton
import ai.enthral.sentinel.ui.components.SentinelIcons
import ai.enthral.sentinel.ui.components.SentinelSheet
import ai.enthral.sentinel.ui.components.SentinelTextField
import ai.enthral.sentinel.ui.components.StatusLine
import ai.enthral.sentinel.ui.components.ToggleRow
import ai.enthral.sentinel.ui.components.formatTimestamp
import ai.enthral.sentinel.ui.components.rememberHaptics
import ai.enthral.sentinel.ui.theme.Radii
import ai.enthral.sentinel.ui.theme.SentinelTheme
import ai.enthral.sentinel.ui.theme.Spacing
import kotlinx.coroutines.delay

/** Invite codes live for 10 minutes (backend contract); drives the countdown ring. */
private const val INVITE_LIFETIME_SECONDS = 600f

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

private val Inset = Modifier.padding(horizontal = Spacing.inset)

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
    val ready = state.links as? LinksState.Ready
    val noLinks = ready != null && ready.links.guardians.isEmpty() && ready.links.protecting.isEmpty()
    LargeTitleList(title = stringResource(R.string.nav_family), subtitle = stringResource(R.string.family_intro)) {
        if (noLinks && state.invite == null) {
            item(key = "illustration") {
                Box(Modifier.fillMaxWidth().padding(vertical = Spacing.x1), contentAlignment = Alignment.Center) {
                    FamilyIllustration(size = 132.dp)
                }
            }
        }
        // --- Someone protects me ---------------------------------------------------------------------
        item(key = "me") {
            GroupedSection(header = stringResource(R.string.family_protect_me_title), footer = stringResource(R.string.family_protect_me_body)) {
                ToggleRow(
                    title = stringResource(R.string.family_consent_title),
                    subtitle = stringResource(R.string.family_consent_body),
                    checked = state.consent,
                    onCheckedChange = onConsent,
                )
            }
        }
        item(key = "invite") { InviteBlock(state, onCreateInvite, Inset.padding(top = Spacing.x2)) }
        item(key = "guardians") {
            GroupedSection(header = stringResource(R.string.family_my_guardians)) {
                when (val l = state.links) {
                    is LinksState.Ready ->
                        if (l.links.guardians.isEmpty()) {
                            GroupedRow(stringResource(R.string.family_no_guardians), titleColor = SentinelTheme.palette.secondaryLabel)
                        } else {
                            l.links.guardians.forEachIndexed { i, g ->
                                if (i > 0) GroupedDivider(IconRowDividerInset)
                                GuardianRow(g, state.workingLinkId == g.id) { confirmUnlink = g.id }
                            }
                        }
                    LinksState.Loading -> GroupedRow(stringResource(R.string.loading_cd), titleColor = SentinelTheme.palette.secondaryLabel)
                    LinksState.Failed -> LinksFailed(onRetryLinks)
                }
            }
        }

        // --- I protect someone -----------------------------------------------------------------------
        item(key = "someone-h") {
            Column(Inset.padding(top = Spacing.x3)) {
                GroupedHeader(stringResource(R.string.family_protect_someone_title))
                GroupedFooter(stringResource(R.string.family_protect_someone_body), Modifier.padding(bottom = Spacing.x15))
            }
        }
        item(key = "someone") {
            Column(Inset, verticalArrangement = Arrangement.spacedBy(Spacing.x15)) {
                CodeEntry(state.code, onCode, enabled = !state.accepting, isError = state.acceptError == FamilyError.InvalidCode)
                SentinelTextField(
                    value = state.name,
                    onValueChange = onName,
                    label = stringResource(R.string.family_name_label),
                    supporting = stringResource(R.string.family_name_hint),
                    enabled = !state.accepting,
                    singleLine = true,
                )
                PrimaryButton(stringResource(R.string.family_link), onAccept, Modifier.fillMaxWidth(), enabled = state.canAccept)
                state.acceptError?.let { StatusLine(familyErrorMessage(it), SentinelIcons.Info, SentinelTheme.colors[Tier.Critical].content) }
                if (state.accepted) StatusLine(stringResource(R.string.family_linked_success), SentinelIcons.ShieldCheck, SentinelTheme.palette.accent)
                Text(stringResource(R.string.family_push_note), style = SentinelTheme.type.footnote, color = SentinelTheme.palette.secondaryLabel)
            }
        }
        item(key = "protecting") {
            GroupedSection(header = stringResource(R.string.family_people_i_protect)) {
                when (val l = state.links) {
                    is LinksState.Ready ->
                        if (l.links.protecting.isEmpty()) {
                            GroupedRow(stringResource(R.string.family_no_protecting), titleColor = SentinelTheme.palette.secondaryLabel)
                        } else {
                            l.links.protecting.forEachIndexed { i, p ->
                                if (i > 0) GroupedDivider(IconRowDividerInset)
                                ProtecteeRow(i + 1, p, state.workingLinkId == p.id) { confirmUnlink = p.id }
                            }
                        }
                    LinksState.Loading -> GroupedRow(stringResource(R.string.loading_cd), titleColor = SentinelTheme.palette.secondaryLabel)
                    LinksState.Failed -> LinksFailed(onRetryLinks)
                }
            }
        }
    }

    confirmUnlink?.let { id ->
        val isGuardian = ready?.links?.guardians?.any { it.id == id } == true
        ConfirmSheet(
            title = stringResource(if (isGuardian) R.string.family_remove_title else R.string.family_stop_title),
            body = stringResource(if (isGuardian) R.string.family_remove_body else R.string.family_stop_body),
            confirmLabel = stringResource(if (isGuardian) R.string.family_remove else R.string.family_stop),
            onConfirm = { confirmUnlink = null; onUnlink(id) },
            onDismiss = { confirmUnlink = null },
        )
    }
    state.unlinkError?.let { err ->
        SentinelSheet(onDismiss = onDismissUnlinkError) {
            Text(stringResource(R.string.error_title), style = SentinelTheme.type.title2, modifier = Modifier.semantics { heading() })
            Text(familyErrorMessage(err), style = SentinelTheme.type.callout, color = SentinelTheme.palette.secondaryLabel)
            PrimaryButton(stringResource(R.string.action_got_it), onDismissUnlinkError, Modifier.fillMaxWidth().padding(top = Spacing.x1))
        }
    }
}

/** The invite: a large code in fixed cells, a countdown ring, copy + share, and a quiet "new code". */
@Composable
private fun InviteBlock(state: FamilyUiState, onCreate: () -> Unit, modifier: Modifier) {
    val p = SentinelTheme.palette
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val haptics = rememberHaptics()
    val invite = state.invite
    Column(modifier.fillMaxWidth()) {
        if (invite != null) {
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(Radii.extraLarge)).background(p.elevated)
                    .padding(horizontal = Spacing.x2, vertical = Spacing.x3),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CodeDisplay(invite.code, dimmed = state.inviteExpired)
                Spacer(Modifier.height(Spacing.x15))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.x1)) {
                    if (!state.inviteExpired) CountdownRing(state.remainingSeconds / INVITE_LIFETIME_SECONDS)
                    Text(
                        if (state.inviteExpired) stringResource(R.string.family_code_expired)
                        else stringResource(R.string.family_code_expires, formatCountdown(state.remainingSeconds)),
                        style = SentinelTheme.type.subhead.copy(fontWeight = FontWeight.Medium, fontFeatureSettings = "tnum"),
                        color = if (state.inviteExpired) SentinelTheme.colors[Tier.Critical].content else p.secondaryLabel,
                    )
                }
                if (!state.inviteExpired) {
                    val shareText = stringResource(R.string.family_share_text, invite.code)
                    val chooser = stringResource(R.string.family_share_chooser)
                    Spacer(Modifier.height(Spacing.x25))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.x15)) {
                        SecondaryButton(
                            stringResource(R.string.family_copy),
                            { clipboard.setText(AnnotatedString(invite.code)); haptics.confirm() },
                            Modifier.weight(1f),
                            icon = SentinelIcons.Copy,
                        )
                        PrimaryButton(
                            stringResource(R.string.family_share),
                            {
                                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, shareText)
                                context.startActivity(Intent.createChooser(send, chooser))
                            },
                            Modifier.weight(1f),
                            icon = SentinelIcons.Share,
                        )
                    }
                }
                Spacer(Modifier.height(Spacing.x05))
                PlainButton(stringResource(R.string.family_regenerate), onCreate, enabled = state.consent && !state.creating)
            }
        } else {
            PrimaryButton(stringResource(R.string.family_create_code), onCreate, Modifier.fillMaxWidth(), enabled = state.consent && !state.creating)
            if (!state.consent) {
                Text(
                    stringResource(R.string.family_consent_required),
                    style = SentinelTheme.type.footnote,
                    color = p.secondaryLabel,
                    modifier = Modifier.padding(start = Spacing.x2, top = Spacing.x1),
                )
            }
        }
        state.inviteError?.let {
            StatusLine(familyErrorMessage(it), SentinelIcons.Info, SentinelTheme.colors[Tier.Critical].content, Modifier.padding(top = Spacing.x15))
        }
    }
}

fun formatCountdown(seconds: Long): String = "%02d:%02d".format(seconds / 60, seconds % 60)

@Composable
private fun RemoveButton(text: String, enabled: Boolean, onClick: () -> Unit) =
    PlainButton(text, onClick, enabled = enabled, color = SentinelTheme.colors[Tier.Critical].content)

@Composable
private fun GuardianRow(g: Guardian, working: Boolean, onRemove: () -> Unit) {
    GroupedRow(
        title = g.name,
        subtitle = stringResource(R.string.family_linked_on, formatTimestamp(g.linkedAt)),
        icon = SentinelIcons.Person,
        trailing = { RemoveButton(stringResource(R.string.family_remove), !working, onRemove) },
    )
}

@Composable
private fun ProtecteeRow(index: Int, p: Protectee, working: Boolean, onStop: () -> Unit) {
    GroupedRow(
        title = stringResource(R.string.family_person, index),
        subtitle = stringResource(R.string.family_linked_on, formatTimestamp(p.linkedAt)),
        icon = SentinelIcons.Person,
        trailing = { RemoveButton(stringResource(R.string.family_stop), !working, onStop) },
    )
}

@Composable
private fun LinksFailed(onRetry: () -> Unit) {
    Column(Modifier.padding(vertical = Spacing.x1)) {
        NoteCard(stringResource(R.string.family_links_failed), icon = SentinelIcons.CloudOff)
        PlainButton(stringResource(R.string.action_retry), onRetry, Modifier.padding(start = Spacing.x1))
    }
}
