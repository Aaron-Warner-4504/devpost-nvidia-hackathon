package ai.enthral.sentinel.ui.placeholder

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import ai.enthral.sentinel.ui.components.EmptyState

/** Stand-in for screens that arrive in later phases. */
@Composable
fun PlaceholderScreen(@StringRes title: Int, @StringRes body: Int) {
    Box(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()), Alignment.Center) {
        EmptyState(title = stringResource(title), body = stringResource(body))
    }
}
