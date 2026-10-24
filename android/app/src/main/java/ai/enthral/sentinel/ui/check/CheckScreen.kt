package ai.enthral.sentinel.ui.check

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import ai.enthral.sentinel.R
import ai.enthral.sentinel.ui.common.AdaptiveBox
import ai.enthral.sentinel.ui.common.graphViewModel
import ai.enthral.sentinel.ui.components.NoteCard
import ai.enthral.sentinel.ui.components.PrimaryButton
import ai.enthral.sentinel.ui.components.SecondaryButton
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
    AdaptiveBox(Modifier.statusBarsPadding()) {
        LazyColumn(
            contentPadding = PaddingValues(horizontal = Spacing.gutter, vertical = Spacing.x2),
            verticalArrangement = Arrangement.spacedBy(Spacing.x2),
        ) {
            item {
                Text(
                    stringResource(R.string.check_title),
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.semantics { heading() },
                )
            }
            item {
                Text(
                    stringResource(R.string.check_intro),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    val modes = CheckMode.entries
                    modes.forEachIndexed { i, mode ->
                        SegmentedButton(
                            selected = state.mode == mode,
                            onClick = { onMode(mode) },
                            shape = SegmentedButtonDefaults.itemShape(i, modes.size),
                            enabled = !state.loading,
                        ) {
                            Text(stringResource(if (mode == CheckMode.Text) R.string.check_mode_text else R.string.check_mode_screenshot))
                        }
                    }
                }
            }
            if (state.mode == CheckMode.Text) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(Spacing.x1)) {
                        OutlinedTextField(
                            value = state.text,
                            onValueChange = onText,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 140.dp),
                            enabled = !state.loading,
                            label = { Text(stringResource(R.string.check_text_label)) },
                            placeholder = { Text(stringResource(R.string.check_text_hint)) },
                            supportingText = {
                                Text(stringResource(R.string.check_counter, state.text.length, CheckViewModel.MAX_TEXT))
                            },
                            isError = state.error == CheckError.TextTooLong,
                            minLines = 5,
                            maxLines = 12,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.x1)) {
                            SecondaryButton(
                                stringResource(R.string.check_paste),
                                { clipboard.getText()?.text?.let(onText) },
                                enabled = !state.loading,
                                iconRes = R.drawable.ic_paste,
                            )
                            if (state.text.isNotEmpty() || state.sender.isNotEmpty()) {
                                TextButton(onClick = onClearText, enabled = !state.loading, modifier = Modifier.heightIn(min = Spacing.minTouchTarget)) {
                                    Text(stringResource(R.string.check_clear))
                                }
                            }
                        }
                        OutlinedTextField(
                            value = state.sender,
                            onValueChange = onSender,
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !state.loading,
                            singleLine = true,
                            label = { Text(stringResource(R.string.check_sender_label)) },
                            supportingText = { Text(stringResource(R.string.check_sender_hint)) },
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Text),
                        )
                    }
                }
            } else {
                item { ScreenshotPicker(state, onPickImage, onClearImage) }
            }
            state.error?.let { err ->
                item {
                    Card(
                        Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        ),
                    ) {
                        Row(Modifier.padding(Spacing.x2), horizontalArrangement = Arrangement.spacedBy(Spacing.x2)) {
                            Icon(painterResource(R.drawable.ic_tier_high), null)
                            Column(verticalArrangement = Arrangement.spacedBy(Spacing.x05)) {
                                Text(stringResource(R.string.check_error_title), style = MaterialTheme.typography.titleSmall)
                                Text(checkErrorMessage(err), style = MaterialTheme.typography.bodyMedium)
                                if (err != CheckError.ImageUnreadable && err != CheckError.NoTextFound) {
                                    Text(stringResource(R.string.check_error_kept), style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
            }
            item {
                if (state.loading) {
                    Column(verticalArrangement = Arrangement.spacedBy(Spacing.x1)) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text(stringResource(R.string.check_loading), style = MaterialTheme.typography.bodyMedium)
                        SecondaryButton(stringResource(R.string.action_cancel), onCancel, Modifier.fillMaxWidth())
                    }
                } else {
                    PrimaryButton(stringResource(R.string.check_submit), onSubmit, Modifier.fillMaxWidth(), enabled = state.canSubmit)
                }
            }
            item { NoteCard(stringResource(R.string.check_privacy_note)) }
        }
    }
}

@Composable
private fun ScreenshotPicker(state: CheckUiState, onPick: () -> Unit, onClear: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.x1)) {
        if (state.imageUri == null) {
            SecondaryButton(stringResource(R.string.check_choose_screenshot), onPick, Modifier.fillMaxWidth(), enabled = !state.loading, iconRes = R.drawable.ic_image)
            Text(
                stringResource(R.string.check_screenshot_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest)) {
                Column(Modifier.padding(Spacing.x1), verticalArrangement = Arrangement.spacedBy(Spacing.x1)) {
                    val bmp = state.thumbnail
                    if (bmp != null) {
                        Image(
                            bmp.asImageBitmap(),
                            contentDescription = stringResource(R.string.check_preview_cd),
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp),
                        )
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        SecondaryButton(stringResource(R.string.check_change_screenshot), onPick, enabled = !state.loading)
                        IconButton(onClick = onClear, enabled = !state.loading) {
                            Icon(Icons.Filled.Close, stringResource(R.string.check_remove_screenshot))
                        }
                    }
                }
            }
        }
    }
}
