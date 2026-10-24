package ai.enthral.sentinel.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import ai.enthral.sentinel.R
import ai.enthral.sentinel.ui.theme.Spacing

/**
 * Six-cell code field backed by ONE text field: typing auto-advances, backspace steps back and pasting a whole
 * code (even with spaces/dashes) fills every cell. The caller normalises the text.
 */
@Composable
fun CodeEntry(
    code: String,
    onCodeChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    isError: Boolean = false,
    length: Int = 6,
) {
    var focused by remember { mutableStateOf(false) }
    val description = stringResource(R.string.family_code_field, code.length, length)
    BasicTextField(
        value = code,
        onValueChange = onCodeChange,
        enabled = enabled,
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.Characters,
            imeAction = ImeAction.Next,
            autoCorrectEnabled = false,
        ),
        cursorBrush = SolidColor(androidx.compose.ui.graphics.Color.Transparent),
        textStyle = MaterialTheme.typography.headlineSmall.copy(color = androidx.compose.ui.graphics.Color.Transparent),
        modifier = modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused }
            .semantics { contentDescription = description },
        decorationBox = {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                repeat(length) { i ->
                    val active = focused && i == code.length.coerceAtMost(length - 1)
                    Surface(
                        Modifier.weight(1f).heightIn(min = 56.dp),
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.surfaceContainerLowest,
                        border = BorderStroke(
                            if (active || isError) 2.dp else 1.dp,
                            when {
                                isError -> MaterialTheme.colorScheme.error
                                active -> MaterialTheme.colorScheme.primary
                                else -> MaterialTheme.colorScheme.outline
                            },
                        ),
                    ) {
                        Box(Modifier.fillMaxWidth().heightIn(min = 56.dp), contentAlignment = Alignment.Center) {
                            Text(
                                code.getOrNull(i)?.toString() ?: "",
                                style = MaterialTheme.typography.headlineSmall,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                }
            }
        },
    )
}
