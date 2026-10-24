package ai.enthral.sentinel.ui.components

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import ai.enthral.sentinel.domain.Tier
import ai.enthral.sentinel.ui.theme.Radii
import ai.enthral.sentinel.ui.theme.SentinelTheme
import ai.enthral.sentinel.ui.theme.Spacing

private val ButtonShape = RoundedCornerShape(Radii.medium)
private val ButtonHeight = 52.dp
private val ButtonPadding = PaddingValues(horizontal = Spacing.x25, vertical = Spacing.x15)

@Composable
private fun ButtonContent(text: String, icon: ImageVector?, iconRes: Int?) {
    when {
        icon != null -> Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
        iconRes != null -> Icon(painterResource(iconRes), contentDescription = null, modifier = Modifier.size(20.dp))
    }
    if (icon != null || iconRes != null) Spacer(Modifier.width(Spacing.x1))
    Text(text, style = SentinelTheme.type.button, textAlign = TextAlign.Center)
}

@Composable
private fun BaseButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier,
    enabled: Boolean,
    container: Color,
    content: Color,
    icon: ImageVector?,
    iconRes: Int?,
) {
    val interaction = remember { MutableInteractionSource() }
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = ButtonHeight).pressScale(interaction),
        shape = ButtonShape,
        contentPadding = ButtonPadding,
        interactionSource = interaction,
        elevation = null,
        colors = ButtonDefaults.buttonColors(
            containerColor = container,
            contentColor = content,
            disabledContainerColor = container.copy(alpha = 0.38f),
            disabledContentColor = content.copy(alpha = 0.75f),
        ),
    ) { ButtonContent(text, icon, iconRes) }
}

/** The one filled, accent-coloured action on a screen. */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    iconRes: Int? = null,
    icon: ImageVector? = null,
) = BaseButton(text, onClick, modifier, enabled, SentinelTheme.palette.accent, SentinelTheme.palette.onAccent, icon, iconRes)

/** Tinted (accent on a soft accent wash): secondary actions, and equal-weight pairs such as feedback. */
@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    iconRes: Int? = null,
    icon: ImageVector? = null,
) = BaseButton(text, onClick, modifier, enabled, SentinelTheme.palette.accentSoft, SentinelTheme.palette.accent, icon, iconRes)

/** Calm destructive action: crimson text on a soft crimson wash, never a red slab. Lives in confirmation sheets. */
@Composable
fun DestructiveButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) =
    BaseButton(text, onClick, modifier, enabled, SentinelTheme.colors[Tier.Critical].container, SentinelTheme.colors[Tier.Critical].content, null, null)

/** Text-only accent action (links, "Skip", "Back"). */
@Composable
fun PlainButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    color: Color = SentinelTheme.palette.accent,
    contentPadding: PaddingValues = PaddingValues(horizontal = Spacing.x15, vertical = Spacing.x1),
    content: (@Composable RowScope.() -> Unit)? = null,
) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = Spacing.minTouchTarget),
        shape = RoundedCornerShape(Radii.small),
        contentPadding = contentPadding,
        colors = ButtonDefaults.textButtonColors(contentColor = color, disabledContentColor = SentinelTheme.palette.tertiaryLabel),
    ) {
        if (content != null) content() else Text(text, style = SentinelTheme.type.headline.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Medium))
    }
}

/** Round 48-dp icon action on a quiet fill (paste, photo, copy, share). */
@Composable
fun IconAction(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    filled: Boolean = true,
) {
    val p = SentinelTheme.palette
    val interaction = remember { MutableInteractionSource() }
    IconButton(
        onClick = onClick,
        enabled = enabled,
        interactionSource = interaction,
        modifier = modifier.size(Spacing.minTouchTarget).pressScale(interaction, 0.92f),
        colors = IconButtonDefaults.iconButtonColors(
            containerColor = if (filled) p.accentSoft else Color.Transparent,
            contentColor = p.accent,
            disabledContainerColor = if (filled) p.elevatedSecondary else Color.Transparent,
            disabledContentColor = p.tertiaryLabel,
        ),
    ) { Icon(icon, contentDescription, Modifier.size(22.dp)) }
}
