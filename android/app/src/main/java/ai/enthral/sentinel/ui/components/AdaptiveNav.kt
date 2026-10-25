package ai.enthral.sentinel.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import ai.enthral.sentinel.ui.common.rememberReducedMotion
import ai.enthral.sentinel.ui.theme.LocalGlass
import ai.enthral.sentinel.ui.theme.Motion
import ai.enthral.sentinel.ui.theme.Radii
import ai.enthral.sentinel.ui.theme.SentinelTheme
import ai.enthral.sentinel.ui.theme.Spacing

/** Android window width classes (developer.android.com: compact < 600 dp, medium < 840 dp, expanded). */
enum class WidthClass { Compact, Medium, Expanded }

fun widthClassOf(width: Dp): WidthClass = when {
    width < 600.dp -> WidthClass.Compact
    width < 840.dp -> WidthClass.Medium
    else -> WidthClass.Expanded
}

class NavItem(val label: String, val icon: ImageVector, val selectedIcon: ImageVector = icon)

/** Floating bar height + its bottom margin; the root adds the system navigation inset. */
val FloatingBarHeight = 64.dp
val FloatingBarMargin = 10.dp

private val BarShape = RoundedCornerShape(Radii.extraLarge)

/** Caps the nav labels at 1.3x font scale so they never clip; TalkBack still reads the full names. */
@Composable
private fun CappedFontScale(content: @Composable () -> Unit) {
    val density = LocalDensity.current
    CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = minOf(density.fontScale, 1.3f)), content = content)
}

@Composable
private fun Modifier.glassEdge(shape: androidx.compose.ui.graphics.Shape): Modifier {
    if (!LocalGlass.current.glass) return this
    val e = SentinelTheme.palette.glassEdge
    return border(1.dp, Brush.verticalGradient(listOf(e, e.copy(alpha = 0f), e.copy(alpha = e.alpha * 0.3f))), shape)
}

/**
 * Compact widths: a floating, inset glass pill in thumb reach. Content scrolls beneath it and is blurred; the
 * selected tab sits on a soft accent capsule that glides between tabs.
 */
@Composable
fun FloatingTabBar(items: List<NavItem>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val p = SentinelTheme.palette
    val haptics = rememberHaptics()
    val reduced = rememberReducedMotion()
    CappedFontScale {
        Box(modifier.fillMaxWidth().navigationBarsPadding().padding(start = Spacing.inset, end = Spacing.inset, bottom = FloatingBarMargin)) {
            BoxWithConstraints(
                Modifier.fillMaxWidth().heightIn(min = FloatingBarHeight)
                    .shadow(18.dp, BarShape, clip = false, ambientColor = p.shadow, spotColor = p.shadow)
                    .glassBar(BarShape).glassEdge(BarShape)
                    .padding(6.dp),
            ) {
                val segment = maxWidth / items.size
                val x by animateDpAsState(segment * selected.coerceAtLeast(0), if (reduced) snap() else Motion.gentle(), label = "capsule")
                if (selected >= 0) {
                    Box(
                        Modifier.matchParentSize().padding(0.dp),
                    ) {
                        Box(
                            Modifier.offset { IntOffset(x.roundToPx(), 0) }.width(segment).fillMaxHeight()
                                .background(p.accentSoft, RoundedCornerShape(Radii.extraLarge - 6.dp)),
                        )
                    }
                }
                Row(Modifier.fillMaxWidth().selectableGroup()) {
                    items.forEachIndexed { i, item ->
                        val isSelected = i == selected
                        val tint = if (isSelected) p.accent else p.secondaryLabel
                        Column(
                            Modifier.weight(1f).heightIn(min = 52.dp)
                                .selectable(
                                    selected = isSelected,
                                    role = Role.Tab,
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                    onClick = { if (!isSelected) haptics.tick(); onSelect(i) },
                                )
                                .padding(vertical = 5.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Icon(if (isSelected) item.selectedIcon else item.icon, null, tint = tint, modifier = Modifier.size(24.dp))
                            Text(
                                item.label,
                                style = SentinelTheme.type.caption.copy(fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium),
                                color = tint,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Medium widths: a glass rail at the leading edge (icons over labels, capsule behind the selected icon). */
@Composable
fun NavRail(items: List<NavItem>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val p = SentinelTheme.palette
    val haptics = rememberHaptics()
    val shape = RoundedCornerShape(Radii.extraLarge)
    CappedFontScale {
        Column(
            modifier.fillMaxHeight().statusBarsPadding().navigationBarsPadding()
                .padding(start = Spacing.x15, top = Spacing.x15, bottom = Spacing.x15)
                .width(84.dp).glass(shape).verticalScroll(rememberScrollState())
                .padding(vertical = Spacing.x2).selectableGroup(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.x1),
        ) {
            Box(Modifier.size(40.dp).background(p.accent, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                Icon(SentinelIcons.ShieldCheck, null, tint = p.onAccent, modifier = Modifier.size(24.dp))
            }
            Spacer(Modifier.height(Spacing.x2))
            items.forEachIndexed { i, item ->
                val isSelected = i == selected
                val tint = if (isSelected) p.accent else p.secondaryLabel
                Column(
                    Modifier.fillMaxWidth().heightIn(min = 64.dp)
                        .selectable(isSelected, role = Role.Tab) { if (!isSelected) haptics.tick(); onSelect(i) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
                ) {
                    Box(
                        Modifier.width(56.dp).height(32.dp)
                            .background(if (isSelected) p.accentSoft else androidx.compose.ui.graphics.Color.Transparent, RoundedCornerShape(16.dp)),
                        contentAlignment = Alignment.Center,
                    ) { Icon(if (isSelected) item.selectedIcon else item.icon, null, tint = tint, modifier = Modifier.size(24.dp)) }
                    Text(
                        item.label,
                        style = SentinelTheme.type.caption.copy(fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium),
                        color = tint,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/**
 * Expanded widths: the rail grows into a labelled glass sidebar with the app mark, the five destinations and a
 * secondary group ([secondary], e.g. Demo and Data & privacy) under its own header.
 */
@Composable
fun NavSidebar(
    appName: String,
    items: List<NavItem>,
    selected: Int,
    onSelect: (Int) -> Unit,
    secondaryTitle: String,
    secondary: List<Pair<NavItem, () -> Unit>>,
    footer: String?,
    modifier: Modifier = Modifier,
) {
    val p = SentinelTheme.palette
    val haptics = rememberHaptics()
    val shape = RoundedCornerShape(Radii.extraLarge)
    Column(
        modifier.fillMaxHeight().statusBarsPadding().navigationBarsPadding()
            .padding(start = Spacing.x15, top = Spacing.x15, bottom = Spacing.x15)
            .width(264.dp).glass(shape).verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.x15, vertical = Spacing.x25),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(Modifier.padding(horizontal = Spacing.x1, vertical = Spacing.x1), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.x15)) {
            Box(Modifier.size(40.dp).background(p.accent, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                Icon(SentinelIcons.ShieldCheck, null, tint = p.onAccent, modifier = Modifier.size(24.dp))
            }
            Text(appName, style = SentinelTheme.type.title3, color = p.label, modifier = Modifier.semantics { heading() })
        }
        Spacer(Modifier.height(Spacing.x2))
        Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            items.forEachIndexed { i, item ->
                SidebarRow(item, selected = i == selected) { if (i != selected) haptics.tick(); onSelect(i) }
            }
        }
        if (secondary.isNotEmpty()) {
            Text(
                secondaryTitle,
                style = SentinelTheme.type.footnote.copy(fontWeight = FontWeight.SemiBold),
                color = p.secondaryLabel,
                modifier = Modifier.padding(start = Spacing.x15, top = Spacing.x3, bottom = Spacing.x1).semantics { heading() },
            )
            secondary.forEach { (item, onClick) -> SidebarRow(item, selected = false, onClick = onClick, role = Role.Button) }
        }
        if (footer != null) {
            Spacer(Modifier.height(Spacing.x3))
            Text(footer, style = SentinelTheme.type.footnote, color = p.secondaryLabel, modifier = Modifier.padding(start = Spacing.x15))
        }
    }
}

@Composable
private fun SidebarRow(item: NavItem, selected: Boolean, role: Role = Role.Tab, onClick: () -> Unit) {
    val p = SentinelTheme.palette
    val tint = if (selected) p.accent else p.label
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp)
            .background(if (selected) p.accentSoft else androidx.compose.ui.graphics.Color.Transparent, RoundedCornerShape(Radii.medium))
            .selectable(selected, role = role, onClick = onClick)
            .padding(horizontal = Spacing.x15),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.x15),
    ) {
        Icon(if (selected) item.selectedIcon else item.icon, null, tint = if (selected) p.accent else p.secondaryLabel, modifier = Modifier.size(24.dp))
        Text(
            item.label,
            style = SentinelTheme.type.body.copy(fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal),
            color = tint,
        )
    }
}
