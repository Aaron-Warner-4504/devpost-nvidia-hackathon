package ai.enthral.sentinel.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import ai.enthral.sentinel.R
import ai.enthral.sentinel.ui.common.MaxContentWidth
import ai.enthral.sentinel.ui.common.rememberReducedMotion
import ai.enthral.sentinel.ui.theme.Motion
import ai.enthral.sentinel.ui.theme.Radii
import ai.enthral.sentinel.ui.theme.SentinelTheme
import ai.enthral.sentinel.ui.theme.Spacing

private val BarHeight = 52.dp

/** Large title with heading semantics, as the first thing in a scrolling screen. */
@Composable
fun LargeTitle(text: String, modifier: Modifier = Modifier, subtitle: String? = null) {
    Column(
        modifier.fillMaxWidth().padding(start = Spacing.gutter, end = Spacing.gutter, top = Spacing.x1, bottom = Spacing.x2),
        verticalArrangement = Arrangement.spacedBy(Spacing.x05),
    ) {
        Text(text, style = SentinelTheme.type.largeTitle, color = SentinelTheme.palette.label, modifier = Modifier.semantics { heading() })
        if (subtitle != null) {
            Text(subtitle, style = SentinelTheme.type.callout, color = SentinelTheme.palette.secondaryLabel)
        }
    }
}

/**
 * Compact bar over a scrolling screen. The status-bar strip is always opaque; the bar's own background, title and
 * hairline fade in once [collapsed] (the large title has scrolled away), like a large-title navigation bar.
 */
@Composable
fun CollapsingTopBar(
    title: String,
    collapsed: Boolean,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    background: Color = SentinelTheme.palette.groupedBackground,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val p = SentinelTheme.palette
    val reduced = rememberReducedMotion()
    val fraction by animateFloatAsState(if (collapsed) 1f else 0f, if (reduced) androidx.compose.animation.core.snap() else Motion.standard(), label = "bar")
    val showBar = onBack != null || fraction > 0f
    Column(modifier.fillMaxWidth()) {
        Spacer(Modifier.fillMaxWidth().background(background).statusBarsPadding())
        if (showBar) {
            val barBg = if (onBack != null) background.copy(alpha = fraction.coerceAtLeast(0.0f)) else background.copy(alpha = fraction)
            Box(Modifier.fillMaxWidth().height(BarHeight).background(barBg)) {
                if (onBack != null) {
                    IconButton(onClick = onBack, modifier = Modifier.align(Alignment.CenterStart).padding(start = Spacing.x05)) {
                        Icon(SentinelIcons.Back, stringResource(R.string.action_back), tint = p.accent, modifier = Modifier.size(24.dp))
                    }
                }
                if (fraction > 0.01f) {
                    Text(
                        title,
                        style = SentinelTheme.type.headline,
                        color = p.label,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.align(Alignment.Center).padding(horizontal = 64.dp)
                            .graphicsLayer { alpha = fraction }.clearAndSetSemantics { },
                    )
                }
                Row(Modifier.align(Alignment.CenterEnd).padding(end = Spacing.x05), verticalAlignment = Alignment.CenterVertically, content = actions)
            }
            Box(Modifier.fillMaxWidth().height(Spacing.hairline).graphicsLayer { alpha = fraction }.background(p.separator))
        }
    }
}

/** Top inset for scrolling content under a [CollapsingTopBar]. */
@Composable
fun topContentInset(hasBar: Boolean): Dp =
    WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + if (hasBar) BarHeight else Spacing.x15

/**
 * Tab-root / pushed screen built on a LazyColumn: large title first, compact bar on scroll, optional pull-to-refresh.
 * Items supply their own horizontal padding (grouped lists inset 16 dp, free text 20 dp).
 */
@Composable
fun LargeTitleList(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    state: LazyListState = rememberLazyListState(),
    showLargeTitle: Boolean = true,
    refreshing: Boolean = false,
    onRefresh: (() -> Unit)? = null,
    background: Color = SentinelTheme.palette.groupedBackground,
    bottomPadding: Dp = Spacing.x4,
    actions: @Composable RowScope.() -> Unit = {},
    content: LazyListScope.() -> Unit,
) {
    val density = LocalDensity.current
    val threshold = with(density) { 36.dp.roundToPx() }
    val collapsed by remember(state) {
        derivedStateOf { state.firstVisibleItemIndex > 0 || state.firstVisibleItemScrollOffset > threshold }
    }
    val top = topContentInset(onBack != null)
    Box(modifier.fillMaxSize().background(background)) {
        val list: @Composable () -> Unit = {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                LazyColumn(
                    state = state,
                    modifier = Modifier.widthIn(max = MaxContentWidth).fillMaxSize(),
                    contentPadding = PaddingValues(top = top, bottom = bottomPadding),
                ) {
                    if (showLargeTitle) item(key = "large-title", contentType = "title") { LargeTitle(title, subtitle = subtitle) }
                    content()
                }
            }
        }
        if (onRefresh != null) SentinelRefreshBox(refreshing, onRefresh, topInset = top) { list() } else list()
        CollapsingTopBar(title, collapsed, onBack = onBack, background = background, actions = actions)
    }
}

/** Same chrome for a plain scrolling column (bounded content such as the alert detail). */
@Composable
fun LargeTitleColumn(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    scroll: ScrollState = rememberScrollState(),
    collapseAfter: Dp = 96.dp,
    background: Color = SentinelTheme.palette.groupedBackground,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    val threshold = with(LocalDensity.current) { collapseAfter.roundToPx() }
    val collapsed by remember(scroll) { derivedStateOf { scroll.value > threshold } }
    val top = topContentInset(onBack != null)
    Box(modifier.fillMaxSize().background(background)) {
        content(PaddingValues(top = top))
        CollapsingTopBar(title, collapsed, onBack = onBack, background = background, actions = actions)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SentinelRefreshBox(refreshing: Boolean, onRefresh: () -> Unit, topInset: Dp = 0.dp, content: @Composable () -> Unit) {
    val state = rememberPullToRefreshState()
    val p = SentinelTheme.palette
    PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = onRefresh,
        state = state,
        indicator = {
            PullToRefreshDefaults.Indicator(
                state = state,
                isRefreshing = refreshing,
                containerColor = p.elevated,
                color = p.accent,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = topInset),
            )
        },
    ) { content() }
}

/** Content section header ("Why we flagged this"): sentence case, headline weight, heading semantics. */
@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = SentinelTheme.type.title3,
        color = SentinelTheme.palette.label,
        modifier = modifier.fillMaxWidth().padding(start = Spacing.gutter, end = Spacing.gutter, top = Spacing.x4, bottom = Spacing.x15)
            .semantics { heading() },
    )
}

// ---- Tab bar ---------------------------------------------------------------------------------------------------

class TabBarItem(val label: String, val icon: ImageVector, val selectedIcon: ImageVector)

/**
 * Refined bottom bar: hairline on top, icon + small label, selection shown by accent colour and a heavier icon
 * (no Material pill). Labels are capped at 1.3x font scale so they never clip; TalkBack still reads full names.
 */
@Composable
fun TabBar(items: List<TabBarItem>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val p = SentinelTheme.palette
    val haptics = rememberHaptics()
    val density = LocalDensity.current
    androidx.compose.runtime.CompositionLocalProvider(
        LocalDensity provides Density(density.density, fontScale = minOf(density.fontScale, 1.3f)),
    ) {
        Column(modifier.fillMaxWidth().background(p.elevated)) {
            Box(Modifier.fillMaxWidth().height(Spacing.hairline).background(p.separator))
            Row(Modifier.fillMaxWidth().navigationBarsPadding().heightIn(min = 56.dp).selectableGroup()) {
                items.forEachIndexed { i, item ->
                    val isSelected = i == selected
                    val tint = if (isSelected) p.accent else p.secondaryLabel
                    Column(
                        Modifier.weight(1f).heightIn(min = 56.dp)
                            .selectable(
                                selected = isSelected,
                                role = Role.Tab,
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = { if (!isSelected) haptics.tick(); onSelect(i) },
                            )
                            .padding(top = Spacing.x1, bottom = Spacing.x05),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        Icon(if (isSelected) item.selectedIcon else item.icon, null, tint = tint, modifier = Modifier.size(25.dp))
                        Text(
                            item.label,
                            style = SentinelTheme.type.caption.copy(
                                fontWeight = if (isSelected) androidx.compose.ui.text.font.FontWeight.SemiBold else androidx.compose.ui.text.font.FontWeight.Medium,
                            ),
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

// ---- Segmented control -----------------------------------------------------------------------------------------

/** Two-to-four way choice with a sliding thumb (language, alert filter). Each segment is a selectable radio. */
@Composable
fun <T> SegmentedControl(
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    label: @Composable (T) -> String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val p = SentinelTheme.palette
    val haptics = rememberHaptics()
    val reduced = rememberReducedMotion()
    val index = options.indexOf(selected).coerceAtLeast(0)
    val track = p.label.copy(alpha = if (p.isDark) 0.10f else 0.06f)
    val thumb = if (p.isDark) Color(0xFF3A3D42) else p.elevated
    BoxWithConstraints(modifier.fillMaxWidth().clip(RoundedCornerShape(Radii.medium)).background(track).padding(3.dp)) {
        val segment = maxWidth / options.size
        val x by animateDpAsState(segment * index, if (reduced) androidx.compose.animation.core.snap() else Motion.standard(), label = "thumb")
        Box(Modifier.matchParentSize()) {
            Box(
                Modifier.offset(x = x).width(segment).fillMaxHeight()
                    .softShadow(RoundedCornerShape(Radii.medium - 3.dp))
                    .background(thumb, RoundedCornerShape(Radii.medium - 3.dp)),
            )
        }
        Row(Modifier.fillMaxWidth().selectableGroup()) {
            options.forEach { option ->
                val isSelected = option == selected
                Box(
                    Modifier.weight(1f).heightIn(min = 42.dp)
                        .selectable(
                            selected = isSelected,
                            enabled = enabled,
                            role = Role.RadioButton,
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = { if (!isSelected) { haptics.tick(); onSelect(option) } },
                        )
                        .padding(horizontal = Spacing.x1, vertical = Spacing.x1),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        label(option),
                        style = SentinelTheme.type.subhead.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold),
                        color = if (isSelected) p.label else p.secondaryLabel,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

// ---- Page indicator --------------------------------------------------------------------------------------------

@Composable
fun PageIndicator(count: Int, current: Int, description: String, modifier: Modifier = Modifier) {
    val p = SentinelTheme.palette
    val reduced = rememberReducedMotion()
    Row(
        modifier.clearAndSetSemantics { contentDescription = description },
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(count) { i ->
            val w by animateDpAsState(if (i == current) 22.dp else 7.dp, if (reduced) androidx.compose.animation.core.snap() else Motion.gentle(), label = "dot")
            Box(
                Modifier.height(7.dp).width(w)
                    .background(if (i == current) p.accent else p.label.copy(alpha = if (p.isDark) 0.3f else 0.2f), CircleShape),
            )
        }
    }
}

// ---- Sheets ----------------------------------------------------------------------------------------------------

/** Bottom sheet for secondary flows: lifted surface, 28-dp top corners, a quiet handle, scrollable content. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SentinelSheet(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val p = SentinelTheme.palette
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = p.elevated,
        contentColor = p.label,
        scrimColor = Color.Black.copy(alpha = if (p.isDark) 0.6f else 0.32f),
        shape = RoundedCornerShape(topStart = Radii.extraLarge, topEnd = Radii.extraLarge),
        tonalElevation = 0.dp,
        dragHandle = {
            Box(Modifier.padding(top = Spacing.x15, bottom = Spacing.x1).size(width = 36.dp, height = 5.dp).background(p.label.copy(alpha = 0.18f), CircleShape))
        },
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.x3).padding(bottom = Spacing.x2)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(Spacing.x15),
            content = content,
        )
    }
}

/** Confirmation sheet: title, one sentence, optional extra input, calm destructive action + cancel. */
@Composable
fun ConfirmSheet(
    title: String,
    body: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    destructive: Boolean = true,
    confirmEnabled: Boolean = true,
    dismissEnabled: Boolean = true,
    extra: @Composable ColumnScope.() -> Unit = {},
) {
    SentinelSheet(onDismiss = { if (dismissEnabled) onDismiss() }) {
        Text(title, style = SentinelTheme.type.title2, modifier = Modifier.semantics { heading() })
        Text(body, style = SentinelTheme.type.callout, color = SentinelTheme.palette.secondaryLabel)
        extra()
        Spacer(Modifier.height(Spacing.x1))
        if (destructive) DestructiveButton(confirmLabel, onConfirm, Modifier.fillMaxWidth(), enabled = confirmEnabled)
        else PrimaryButton(confirmLabel, onConfirm, Modifier.fillMaxWidth(), enabled = confirmEnabled)
        PlainButton(stringResource(R.string.action_cancel), onDismiss, Modifier.fillMaxWidth(), enabled = dismissEnabled, color = SentinelTheme.palette.label)
    }
}

/** Space for the system navigation bar on screens without the tab bar. */
@Composable
fun navigationBarInset(): Dp = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

/** Inverse, rounded snackbar (Undo, Copied). */
@Composable
fun SentinelSnackbarHost(state: androidx.compose.material3.SnackbarHostState, modifier: Modifier = Modifier) {
    val p = SentinelTheme.palette
    androidx.compose.material3.SnackbarHost(state, modifier.padding(horizontal = Spacing.inset, vertical = Spacing.x1)) { data ->
        androidx.compose.material3.Snackbar(
            data,
            shape = RoundedCornerShape(Radii.medium),
            containerColor = p.label,
            contentColor = p.groupedBackground,
            actionColor = Color(if (p.isDark) ai.enthral.sentinel.ui.theme.Palettes.light.accent else ai.enthral.sentinel.ui.theme.Palettes.dark.accent),
        )
    }
}
