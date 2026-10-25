package ai.enthral.sentinel.ui.common

import android.provider.Settings
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import ai.enthral.sentinel.AppGraph

val LocalAppGraph = compositionLocalOf<AppGraph> { error("AppGraph not provided") }

/** Creates a ViewModel wired from the [AppGraph], scoped to the current nav back stack entry. */
@Composable
inline fun <reified VM : ViewModel> graphViewModel(
    key: String? = null,
    crossinline create: CreationExtras.(AppGraph) -> VM,
): VM {
    val graph = LocalAppGraph.current
    return viewModel(key = key, factory = viewModelFactory { initializer { create(graph) } })
}

/** Content is capped to a readable width and centred, so tablets and landscape do not stretch it. */
val MaxContentWidth = 640.dp

@Composable
fun AdaptiveBox(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Box(Modifier.widthIn(max = MaxContentWidth).fillMaxHeight(), content = content)
    }
}

/** True when the user turned animations off (developer option / accessibility "remove animations"). */
@Composable
fun rememberReducedMotion(): Boolean {
    val resolver = LocalContext.current.contentResolver
    return remember {
        Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
}

/** Set by the sidebar's "Data and privacy" item; Settings opens its privacy sheet and clears it. */
val LocalPrivacyRequest = androidx.compose.runtime.staticCompositionLocalOf { androidx.compose.runtime.mutableStateOf(false) }

/** The current window's width class (compact / medium / expanded), for screens that adapt (Alerts two-pane). */
val LocalWidthClass = androidx.compose.runtime.staticCompositionLocalOf { ai.enthral.sentinel.ui.components.WidthClass.Compact }
