package ai.enthral.sentinel.ui.theme

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect

/**
 * How much glass the UI may use right now (docs/design.md, Glass and atmosphere).
 * @param glass translucent surfaces over the living atmosphere; false = opaque pass-1 surfaces.
 * @param blur real backdrop blur on the bars (API 31+ only, and only with [glass]).
 * @param motion the atmosphere drifts; false = static.
 */
@Immutable
data class GlassSettings(val glass: Boolean, val blur: Boolean, val motion: Boolean) {
    companion object {
        val Opaque = GlassSettings(glass = false, blur = false, motion = false)
    }
}

val LocalGlass = staticCompositionLocalOf { GlassSettings.Opaque }

/** System conditions that force the opaque fallback (re-read on every resume). */
data class SystemGlassConditions(
    val animationsOff: Boolean,
    val highContrastText: Boolean,
    val powerSave: Boolean,
    val lowRam: Boolean,
    val blurCapable: Boolean,
)

fun readSystemGlassConditions(context: Context): SystemGlassConditions {
    val resolver = context.contentResolver
    val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
    val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
    return SystemGlassConditions(
        animationsOff = Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f,
        // Not public API, but a plain secure setting that is world-readable; absent -> 0.
        highContrastText = runCatching { Settings.Secure.getInt(resolver, "high_text_contrast_enabled", 0) == 1 }.getOrDefault(false),
        powerSave = pm?.isPowerSaveMode == true,
        lowRam = am?.isLowRamDevice == true,
        blurCapable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S,
    )
}

/** Pure decision, unit-testable: any reason to reduce transparency wins. */
fun decideGlass(userReduceTransparency: Boolean, c: SystemGlassConditions): GlassSettings {
    val glass = !userReduceTransparency && !c.animationsOff && !c.highContrastText && !c.powerSave && !c.lowRam
    return GlassSettings(glass = glass, blur = glass && c.blurCapable, motion = glass)
}

@Composable
fun rememberGlassSettings(userReduceTransparency: Boolean): GlassSettings {
    val context = LocalContext.current
    var conditions by remember { mutableStateOf(readSystemGlassConditions(context)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { conditions = readSystemGlassConditions(context) }
    return remember(userReduceTransparency, conditions) { decideGlass(userReduceTransparency, conditions) }
}
