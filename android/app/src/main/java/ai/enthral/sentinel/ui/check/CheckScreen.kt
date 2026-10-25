package ai.enthral.sentinel.ui.check

import ai.enthral.sentinel.ui.components.glass
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import ai.enthral.sentinel.R
import ai.enthral.sentinel.domain.Tier
import ai.enthral.sentinel.ui.common.graphViewModel
import ai.enthral.sentinel.ui.components.GroupedDivider
import ai.enthral.sentinel.ui.components.IconAction
import ai.enthral.sentinel.ui.components.LargeTitleList
import ai.enthral.sentinel.ui.components.NoteCard
import ai.enthral.sentinel.ui.components.PlainButton
import ai.enthral.sentinel.ui.components.PrimaryButton
import ai.enthral.sentinel.ui.components.SentinelIcons
import ai.enthral.sentinel.ui.components.SentinelTextField
import ai.enthral.sentinel.ui.components.TierGlyph
import ai.enthral.sentinel.ui.theme.Radii
import ai.enthral.sentinel.ui.theme.SentinelTheme
import ai.enthral.sentinel.ui.theme.Spacing

@Composable
fun CheckScreen(onOpenResult: (String) -> Unit) {
    val vm = graphViewModel { g -> CheckViewModel.create(g, createSavedStateHandle()) }
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(vm) {
        vm.events.flow.collect { if (it is CheckEvent.OpenResult) onOpenResult(it.alertId) }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) vm.pickImage(uri.toString())
    }
    CheckContent(
        state = state,
        onMode = vm::setMode,
        onText = vm::setText,
        onSender = vm::setSender,
        onPickImage = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
        onClearImage = vm::clearImage,
        onClearText = { vm.setText(""); vm.setSender("") },
        onSubmit = vm::submit,
        onCancel = vm::cancel,
    )
}

@Composable
fun checkErrorMessage(e: CheckError): String = stringResource(
    when (e) {
        CheckError.Offline -> R.string.check_error_offline
        CheckError.AuthFailed -> R.string.error_unauthorized
        CheckError.RateLimited -> R.string.check_error_rate_limited
        CheckError.TextTooLong -> R.string.check_error_text_too_long
        CheckError.ImageTooLarge -> R.string.check_error_image_too_large
        CheckError.UnsupportedImage -> R.string.check_error_unsupported_image
        CheckError.NoTextFound -> R.string.check_error_no_text
        CheckError.ImageUnreadable -> R.string.check_error_image_unreadable
        CheckError.InvalidInput -> R.string.check_error_invalid
        CheckError.ReadFailed -> R.string.check_error_read_failed
        CheckError.Server -> R.string.check_error_server
    },
)

private val Inset = Modifier.padding(horizontal = Spacing.inset)

/**
 * Focused composer: one large text area with paste / photo as icon actions inside it, an optional sender, one
 * primary "Check". Picking a photo swaps the text area for the screenshot preview; removing it swaps back.
 */
@Composable
fun CheckContent(
    state: CheckUiState,
    onMode: (CheckMode) -> Unit,
    onText: (String) -> Unit,
    onSender: (String) -> Unit,
    onPickImage: () -> Unit,
    onClearImage: () -> Unit,
    onClearText: () -> Unit,
    onSubmit: () -> Unit,
    onCancel: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    val paste: () -> Unit = {
        if (state.mode != CheckMode.Text) onMode(CheckMode.Text)
        clipboard.getText()?.text?.let(onText)
    }
    val photo: () -> Unit = {
        if (state.mode != CheckMode.Screenshot) onMode(CheckMode.Screenshot)
        onPickImage()
    }
    LargeTitleList(title = stringResource(R.string.check_title), subtitle = stringResource(R.string.check_intro)) {
        item(key = "composer") {
            Column(
                Inset.fillMaxWidth().glass(RoundedCornerShape(Radii.extraLarge)),
            ) {
                if (state.mode == CheckMode.Text) {
                    SentinelTextField(
                        value = state.text,
                        onValueChange = onText,
                        label = stringResource(R.string.check_text_label),
                        placeholder = stringResource(R.string.check_text_hint),
                        enabled = !state.loading,
                        isError = state.error == CheckError.TextTooLong,
                        minLines = 6,
                        maxLines = 14,
                        shape = RoundedCornerShape(0.dp),
                        container = androidx.compose.ui.graphics.Color.Transparent,
                        modifier = Modifier.heightIn(min = 180.dp),
                    )
                } else {
                    ScreenshotArea(state, onPickImage)
                }
                ComposerToolbar(state, paste, photo, onClearText, onRemoveImage = { onClearImage(); onMode(CheckMode.Text) })
            }
        }
        if (state.mode == CheckMode.Text) {
            item(key = "sender") {
                SentinelTextField(
                    value = state.sender,
                    onValueChange = onSender,
                    label = stringResource(R.string.check_sender_label),
                    supporting = stringResource(R.string.check_sender_hint),
                    enabled = !state.loading,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                    modifier = Inset.padding(top = Spacing.x15),
                )
            }
        }
        state.error?.let { err -> item(key = "error") { CheckErrorCard(err, Inset.padding(top = Spacing.x2)) } }
        item(key = "submit") {
            Box(Inset.padding(top = Spacing.x3)) {
                if (state.loading) {
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 52.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Spacing.x15),
                    ) {
                        CircularProgressIndicator(Modifier.size(22.dp), color = SentinelTheme.palette.accent, strokeWidth = 2.5.dp)
                        Text(
                            stringResource(R.string.check_loading),
                            style = SentinelTheme.type.callout,
                            color = SentinelTheme.palette.secondaryLabel,
                            modifier = Modifier.weight(1f),
                        )
                        PlainButton(stringResource(R.string.action_cancel), onCancel)
                    }
                } else {
                    PrimaryButton(stringResource(R.string.check_submit), onSubmit, Modifier.fillMaxWidth(), enabled = state.canSubmit)
                }
            }
        }
        item(key = "privacy") { NoteCard(stringResource(R.string.check_privacy_note), Modifier.padding(top = Spacing.x15)) }
    }
}

@Composable
private fun ComposerToolbar(
    state: CheckUiState,
    onPaste: () -> Unit,
    onPhoto: () -> Unit,
    onClearText: () -> Unit,
    onRemoveImage: () -> Unit,
) {
    val p = SentinelTheme.palette
    GroupedDivider()
    Row(
        Modifier.fillMaxWidth().padding(horizontal = Spacing.x1, vertical = Spacing.x1),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.x05),
    ) {
        IconAction(SentinelIcons.Paste, stringResource(R.string.check_paste), onPaste, enabled = !state.loading, filled = false)
        IconAction(SentinelIcons.Photo, stringResource(R.string.check_choose_screenshot), onPhoto, enabled = !state.loading, filled = false)
        Spacer(Modifier.weight(1f))
        if (state.mode == CheckMode.Text) {
            if (state.text.isNotEmpty() || state.sender.isNotEmpty()) {
                PlainButton(stringResource(R.string.check_clear), onClearText, enabled = !state.loading)
            }
            Text(
                stringResource(R.string.check_counter, state.text.length, CheckViewModel.MAX_TEXT),
                style = SentinelTheme.type.footnote,
                color = if (state.error == CheckError.TextTooLong) SentinelTheme.colors[Tier.Critical].content else p.secondaryLabel,
                modifier = Modifier.padding(end = Spacing.x1),
            )
        } else if (state.imageUri != null) {
            IconAction(SentinelIcons.Close, stringResource(R.string.check_remove_screenshot), onRemoveImage, enabled = !state.loading, filled = false)
        } else {
            PlainButton(stringResource(R.string.check_mode_text), onRemoveImage, enabled = !state.loading)
        }
    }
}

@Composable
private fun ScreenshotArea(state: CheckUiState, onPick: () -> Unit) {
    val p = SentinelTheme.palette
    val bmp = state.thumbnail
    if (state.imageUri != null && bmp != null) {
        Column(Modifier.fillMaxWidth().padding(Spacing.x15), horizontalAlignment = Alignment.CenterHorizontally) {
            Image(
                bmp.asImageBitmap(),
                contentDescription = stringResource(R.string.check_preview_cd),
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxWidth().heightIn(max = 340.dp).clip(RoundedCornerShape(Radii.medium)),
            )
            PlainButton(stringResource(R.string.check_change_screenshot), onPick, enabled = !state.loading)
        }
    } else {
        Column(
            Modifier.fillMaxWidth().heightIn(min = 180.dp)
                .clickable(enabled = !state.loading, role = Role.Button, onClick = onPick)
                .padding(Spacing.x3),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Box(Modifier.size(52.dp).background(p.accentSoft, RoundedCornerShape(16.dp)), contentAlignment = Alignment.Center) {
                Icon(SentinelIcons.Photo, null, tint = p.accent, modifier = Modifier.size(28.dp))
            }
            Spacer(Modifier.size(Spacing.x15))
            Text(stringResource(R.string.check_choose_screenshot), style = SentinelTheme.type.headline, color = p.accent)
            Spacer(Modifier.size(Spacing.x05))
            Text(
                stringResource(R.string.check_screenshot_hint),
                style = SentinelTheme.type.footnote,
                color = p.secondaryLabel,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** Calm error: caution glyph, a title, the reason, and a reassurance that nothing typed was lost. */
@Composable
private fun CheckErrorCard(err: CheckError, modifier: Modifier) {
    val p = SentinelTheme.palette
    Row(
        modifier.fillMaxWidth().glass(RoundedCornerShape(Radii.large)).padding(Spacing.x2),
        horizontalArrangement = Arrangement.spacedBy(Spacing.x15),
    ) {
        TierGlyph(Tier.Caution, size = 32.dp, pulse = false)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.x05)) {
            Text(stringResource(R.string.check_error_title), style = SentinelTheme.type.headline, color = p.label, modifier = Modifier.semantics { heading() })
            Text(checkErrorMessage(err), style = SentinelTheme.type.subhead, color = p.label)
            if (err != CheckError.ImageUnreadable && err != CheckError.NoTextFound) {
                Text(stringResource(R.string.check_error_kept), style = SentinelTheme.type.footnote, color = p.secondaryLabel)
            }
        }
    }
}
