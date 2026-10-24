package ai.enthral.sentinel.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import ai.enthral.sentinel.ui.theme.Radii
import ai.enthral.sentinel.ui.theme.SentinelTheme
import ai.enthral.sentinel.ui.theme.Spacing

/** Divider inset for rows that start with an [IconTile]. */
val IconRowDividerInset: Dp = Spacing.x2 + 30.dp + Spacing.x15

/**
 * Inset grouped list (Settings style): optional header above, rows on one lifted rounded surface, optional footer.
 * Rows are separated with [GroupedDivider].
 */
@Composable
fun GroupedSection(
    modifier: Modifier = Modifier,
    header: String? = null,
    footer: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val p = SentinelTheme.palette
    Column(modifier.fillMaxWidth().padding(horizontal = Spacing.inset)) {
        if (header != null) GroupedHeader(header)
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(Radii.large)).background(p.elevated), content = content)
        if (footer != null) GroupedFooter(footer)
    }
}

@Composable
fun GroupedHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = SentinelTheme.type.subhead.copy(fontWeight = FontWeight.SemiBold),
        color = SentinelTheme.palette.secondaryLabel,
        modifier = modifier.fillMaxWidth().padding(start = Spacing.x2, end = Spacing.x2, top = Spacing.x3, bottom = Spacing.x1)
            .semantics { heading() },
    )
}

@Composable
fun GroupedFooter(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = SentinelTheme.type.footnote,
        color = SentinelTheme.palette.secondaryLabel,
        modifier = modifier.fillMaxWidth().padding(start = Spacing.x2, end = Spacing.x2, top = Spacing.x1),
    )
}

@Composable
fun GroupedDivider(inset: Dp = Spacing.x2) {
    Box(Modifier.padding(start = inset).fillMaxWidth().height(Spacing.hairline).background(SentinelTheme.palette.separator))
}

/** Shape of item [index] of [count] in a grouped list built from separate lazy items. */
fun groupedShape(index: Int, count: Int): Shape {
    val r = Radii.large
    return when {
        count == 1 -> RoundedCornerShape(r)
        index == 0 -> RoundedCornerShape(topStart = r, topEnd = r)
        index == count - 1 -> RoundedCornerShape(bottomStart = r, bottomEnd = r)
        else -> RoundedCornerShape(0.dp)
    }
}

/** One row of a grouped list that lives in its own lazy item (keeps stable keys + item animations). */
@Composable
fun GroupedItem(index: Int, count: Int, modifier: Modifier = Modifier, dividerInset: Dp = Spacing.x2, content: @Composable () -> Unit) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = Spacing.inset).clip(groupedShape(index, count))
            .background(SentinelTheme.palette.elevated),
    ) {
        if (index > 0) GroupedDivider(dividerInset)
        content()
    }
}

/** Small rounded tile holding a row's icon (monochrome accent, calm). */
@Composable
fun IconTile(icon: ImageVector, tint: Color = SentinelTheme.palette.accent, background: Color = SentinelTheme.palette.accentSoft) {
    Box(Modifier.size(30.dp).background(background, RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(19.dp))
    }
}

/** Settings-style row: optional icon tile, title + optional subtitle, trailing content, chevron when it navigates. */
@Composable
fun GroupedRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    titleColor: Color = SentinelTheme.palette.label,
    onClick: (() -> Unit)? = null,
    chevron: Boolean = false,
    enabled: Boolean = true,
    trailing: (@Composable () -> Unit)? = null,
) {
    val p = SentinelTheme.palette
    Row(
        modifier.fillMaxWidth()
            .heightIn(min = if (subtitle != null) Spacing.rowHeightTwoLine else Spacing.rowHeight)
            .then(if (onClick != null) Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick) else Modifier)
            .padding(horizontal = Spacing.x2, vertical = Spacing.x15),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.x15),
    ) {
        if (icon != null) IconTile(icon)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = SentinelTheme.type.body, color = if (enabled) titleColor else p.tertiaryLabel)
            if (subtitle != null) Text(subtitle, style = SentinelTheme.type.subhead, color = p.secondaryLabel)
        }
        trailing?.invoke()
        if (chevron) Icon(SentinelIcons.ChevronRight, null, tint = p.tertiaryLabel, modifier = Modifier.size(18.dp))
    }
}

/** Row with a trailing value ("Version  0.1.0"). */
@Composable
fun ValueRow(title: String, value: String, modifier: Modifier = Modifier, icon: ImageVector? = null) {
    GroupedRow(title, modifier, icon = icon, trailing = {
        Text(value, style = SentinelTheme.type.body, color = SentinelTheme.palette.secondaryLabel, modifier = Modifier.padding(start = Spacing.x1))
    })
}

@Composable
fun sentinelSwitchColors() = SwitchDefaults.colors(
    checkedTrackColor = SentinelTheme.palette.accent,
    checkedThumbColor = SentinelTheme.palette.onAccent,
    checkedBorderColor = Color.Transparent,
    uncheckedTrackColor = SentinelTheme.palette.elevatedSecondary,
    uncheckedThumbColor = SentinelTheme.palette.tertiaryLabel,
    uncheckedBorderColor = SentinelTheme.palette.tertiaryLabel,
)

/**
 * Whole row toggles (big target); the switch is decorative inside it. TalkBack reads "title, subtitle, switch, on".
 */
@Composable
fun ToggleRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    val haptics = rememberHaptics()
    val p = SentinelTheme.palette
    Row(
        modifier.fillMaxWidth()
            .heightIn(min = if (subtitle != null) Spacing.rowHeightTwoLine else Spacing.rowHeight)
            .toggleable(value = checked, enabled = enabled, role = Role.Switch) { haptics.toggle(it); onCheckedChange(it) }
            .padding(horizontal = Spacing.x2, vertical = Spacing.x15),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.x15),
    ) {
        if (icon != null) IconTile(icon)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = SentinelTheme.type.body, color = p.label)
            if (subtitle != null) Text(subtitle, style = SentinelTheme.type.subhead, color = p.secondaryLabel)
        }
        Switch(checked = checked, onCheckedChange = null, enabled = enabled, colors = sentinelSwitchColors())
    }
}

/** Filled, borderless text field on a lifted surface (no Material underline). */
@Composable
fun SentinelTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    supporting: String? = null,
    enabled: Boolean = true,
    isError: Boolean = false,
    singleLine: Boolean = false,
    minLines: Int = 1,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    shape: Shape = RoundedCornerShape(Radii.medium),
    container: Color = SentinelTheme.palette.elevated,
    textStyle: androidx.compose.ui.text.TextStyle = SentinelTheme.type.body,
) {
    val p = SentinelTheme.palette
    TextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        enabled = enabled,
        isError = isError,
        singleLine = singleLine,
        minLines = minLines,
        maxLines = maxLines,
        textStyle = textStyle,
        shape = shape,
        keyboardOptions = keyboardOptions,
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it, color = p.tertiaryLabel) } },
        supportingText = supporting?.let { { Text(it) } },
        colors = TextFieldDefaults.colors(
            focusedContainerColor = container,
            unfocusedContainerColor = container,
            disabledContainerColor = container,
            errorContainerColor = container,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            disabledIndicatorColor = Color.Transparent,
            errorIndicatorColor = Color.Transparent,
            focusedLabelColor = p.accent,
            unfocusedLabelColor = p.secondaryLabel,
            cursorColor = p.accent,
            focusedSupportingTextColor = p.secondaryLabel,
            unfocusedSupportingTextColor = p.secondaryLabel,
        ),
    )
}
