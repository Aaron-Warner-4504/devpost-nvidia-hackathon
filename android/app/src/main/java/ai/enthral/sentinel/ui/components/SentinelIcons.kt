package ai.enthral.sentinel.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * Sentinel's icon family: thin (1.6 on a 24 grid), rounded, drawn for this app. One consistent line weight; tab icons
 * have a heavier "selected" twin. Paths are plain SVG path strings so they are easy to review and tweak.
 * Icon tint comes from the caller (`Icon(..., tint = ...)`), the black here is only a placeholder.
 */
object SentinelIcons {
    private fun icon(name: String, vararg paths: String, stroke: Float = 1.6f, mirror: Boolean = false): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f, autoMirror = mirror).apply {
            paths.forEach { d ->
                addPath(
                    pathData = addPathNodes(d),
                    fill = null,
                    stroke = SolidColor(Color.Black),
                    strokeLineWidth = stroke,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                )
            }
        }.build()

    private const val SHIELD = "M12 2.6c2.4 1.4 4.9 2.3 7.4 2.7v6.2c0 4.8-3.1 8.8-7.4 10-4.3-1.2-7.4-5.2-7.4-10V5.3c2.5-.4 5-1.3 7.4-2.7z"
    private const val HOME_1 = "M4 10.2 12 4l8 6.2V19a1.5 1.5 0 0 1-1.5 1.5H15v-5.2a1 1 0 0 0-1-1h-4a1 1 0 0 0-1 1v5.2H5.5A1.5 1.5 0 0 1 4 19z"
    private const val BELL_1 = "M6.2 16.8V11a5.8 5.8 0 0 1 11.6 0v5.8l1.5 1.7H4.7z"
    private const val BELL_2 = "M10 20.6a2.1 2.1 0 0 0 4 0"
    private const val GLASS_1 = "M10.8 4.2a6.6 6.6 0 1 1 0 13.2a6.6 6.6 0 0 1 0-13.2z"
    private const val GLASS_2 = "M15.6 15.6l4.6 4.6"
    private const val GLASS_3 = "M8.2 11l1.8 1.8 3.4-3.6"
    private const val PEOPLE_1 = "M9 11.2a3.4 3.4 0 1 0 0-6.8a3.4 3.4 0 0 0 0 6.8z"
    private const val PEOPLE_2 = "M2.8 19.8c.6-3.4 3.1-5.5 6.2-5.5s5.6 2.1 6.2 5.5"
    private const val PEOPLE_3 = "M15.6 4.6a3.3 3.3 0 0 1 0 6.3"
    private const val PEOPLE_4 = "M17.8 14.6c1.9.7 3.1 2.5 3.4 5.2"
    private const val SLIDERS_1 = "M4 7.5h8.5M17.5 7.5H20M4 16.5h2.5M11.5 16.5H20"
    private const val SLIDERS_2 = "M15 5.3a2.2 2.2 0 1 1 0 4.4a2.2 2.2 0 0 1 0-4.4z"
    private const val SLIDERS_3 = "M9 14.3a2.2 2.2 0 1 1 0 4.4a2.2 2.2 0 0 1 0-4.4z"

    // Tabs (regular + selected weight)
    val Home = icon("home", HOME_1)
    val HomeSelected = icon("home.sel", HOME_1, stroke = 2.1f)
    val Alerts = icon("alerts", BELL_1, BELL_2)
    val AlertsSelected = icon("alerts.sel", BELL_1, BELL_2, stroke = 2.1f)
    val Check = icon("check", GLASS_1, GLASS_2, GLASS_3)
    val CheckSelected = icon("check.sel", GLASS_1, GLASS_2, GLASS_3, stroke = 2.1f)
    val Family = icon("family", PEOPLE_1, PEOPLE_2, PEOPLE_3, PEOPLE_4)
    val FamilySelected = icon("family.sel", PEOPLE_1, PEOPLE_2, PEOPLE_3, PEOPLE_4, stroke = 2.1f)
    val Settings = icon("settings", SLIDERS_1, SLIDERS_2, SLIDERS_3)
    val SettingsSelected = icon("settings.sel", SLIDERS_1, SLIDERS_2, SLIDERS_3, stroke = 2.1f)

    // Shield family: the hero glyph morphs between these
    val Shield = icon("shield", SHIELD)
    val ShieldCheck = icon("shield.check", SHIELD, "M8.6 12.1l2.4 2.4 4.4-4.7")
    val ShieldPlus = icon("shield.plus", SHIELD, "M12 8.8v6M9 11.8h6")
    val ShieldAlert = icon("shield.alert", SHIELD, "M12 8.3v4.4", "M12 15.6v.1")
    val ShieldPause = icon("shield.pause", SHIELD, "M10.2 9.2v5.4M13.8 9.2v5.4")
    val CloudOff = icon(
        "cloud.off",
        "M8.2 8.6a5 5 0 0 1 9.1 2.4 3.8 3.8 0 0 1-.6 7.5H7.4a4.2 4.2 0 0 1-1.6-8.1",
        "M4 4l16 16",
    )

    // Navigation & actions
    val ChevronRight = icon("chevron.right", "M9.5 6l6 6-6 6", mirror = true)
    val Back = icon("back", "M15 5.5 8.5 12l6.5 6.5", stroke = 2f, mirror = true)
    val Close = icon("close", "M6.5 6.5l11 11M17.5 6.5l-11 11")
    val Checkmark = icon("checkmark", "M5 12.6l4.4 4.4L19 7.4", stroke = 2f)
    val Copy = icon(
        "copy",
        "M9.5 8.5h8.5a1.5 1.5 0 0 1 1.5 1.5v8.5a1.5 1.5 0 0 1-1.5 1.5H9.5A1.5 1.5 0 0 1 8 18.5V10a1.5 1.5 0 0 1 1.5-1.5z",
        "M16 5.8V5.5A1.5 1.5 0 0 0 14.5 4H6a1.5 1.5 0 0 0-1.5 1.5V14A1.5 1.5 0 0 0 6 15.5h.3",
    )
    val Share = icon(
        "share",
        "M12 3.8v10.4",
        "M8.3 7.3 12 3.6l3.7 3.7",
        "M8.5 10.5H7A1.5 1.5 0 0 0 5.5 12v7A1.5 1.5 0 0 0 7 20.5h10a1.5 1.5 0 0 0 1.5-1.5v-7a1.5 1.5 0 0 0-1.5-1.5h-1.5",
    )
    val Paste = icon(
        "paste",
        "M9 4.8H7.2A1.5 1.5 0 0 0 5.7 6.3V19a1.5 1.5 0 0 0 1.5 1.5h9.6a1.5 1.5 0 0 0 1.5-1.5V6.3a1.5 1.5 0 0 0-1.5-1.5H15",
        "M9.8 3.3h4.4a.8.8 0 0 1 .8.8v1.4a.8.8 0 0 1-.8.8H9.8a.8.8 0 0 1-.8-.8V4.1a.8.8 0 0 1 .8-.8z",
    )
    val Photo = icon(
        "photo",
        "M5.5 4.5h13A2 2 0 0 1 20.5 6.5v11a2 2 0 0 1-2 2h-13a2 2 0 0 1-2-2v-11a2 2 0 0 1 2-2z",
        "M3.8 16.2l4.4-4.4 3.8 3.8 2.4-2.4 5.8 5.8",
        "M15.5 7.8a1.5 1.5 0 1 1 0 3a1.5 1.5 0 0 1 0-3z",
    )
    val Refresh = icon("refresh", "M19.4 12.6a7.5 7.5 0 1 1-2.1-6.2", "M19.6 4.6v3.9h-3.9")
    val ExternalLink = icon("external", "M8 16l8.5-8.5", "M10 7.5h6.5V14", mirror = true)

    // Content
    val Lock = icon(
        "lock",
        "M7 10.5h10a1.5 1.5 0 0 1 1.5 1.5v7A1.5 1.5 0 0 1 17 20.5H7A1.5 1.5 0 0 1 5.5 19v-7A1.5 1.5 0 0 1 7 10.5z",
        "M8.5 10.5V7.8a3.5 3.5 0 0 1 7 0v2.7",
    )
    val EyeOff = icon(
        "eye.off",
        "M3.2 12s3.2-5.8 8.8-5.8 8.8 5.8 8.8 5.8-3.2 5.8-8.8 5.8S3.2 12 3.2 12z",
        "M12 9.6a2.4 2.4 0 1 1 0 4.8a2.4 2.4 0 0 1 0-4.8z",
        "M4.5 4.5l15 15",
    )
    val Trash = icon(
        "trash",
        "M4.5 6.5h15",
        "M9.5 6.5V5a1 1 0 0 1 1-1h3a1 1 0 0 1 1 1v1.5",
        "M6.5 6.5l.8 12.2a1.5 1.5 0 0 0 1.5 1.3h6.4a1.5 1.5 0 0 0 1.5-1.3l.8-12.2",
    )
    val Info = icon("info", "M12 3.5a8.5 8.5 0 1 1 0 17a8.5 8.5 0 0 1 0-17z", "M12 11v5", "M12 8v.1")
    val Phone = icon(
        "phone",
        "M6.6 3.8h2.2l1.5 3.9-2 1.3a11 11 0 0 0 5 5l1.3-2 3.9 1.5v2.2a2 2 0 0 1-2 2A15 15 0 0 1 4.6 5.8a2 2 0 0 1 2-2z",
    )
    val Message = icon(
        "message",
        "M5.5 4.8h13a2 2 0 0 1 2 2v8.4a2 2 0 0 1-2 2h-7l-4.3 3.2v-3.2H5.5a2 2 0 0 1-2-2V6.8a2 2 0 0 1 2-2z",
    )
    val Globe = icon(
        "globe",
        "M12 3.5a8.5 8.5 0 1 1 0 17a8.5 8.5 0 0 1 0-17z",
        "M3.6 12h16.8",
        "M12 3.6c2.2 2.3 3.4 5.1 3.4 8.4s-1.2 6.1-3.4 8.4c-2.2-2.3-3.4-5.1-3.4-8.4S9.8 5.9 12 3.6z",
    )
    val Moon = icon("moon", "M19.6 14.6A8.1 8.1 0 0 1 9.4 4.4a8.1 8.1 0 1 0 10.2 10.2z")
    val Sparkles = icon(
        "sparkles",
        "M11 4l1.6 4.6 4.6 1.6-4.6 1.6L11 16.4l-1.6-4.6L4.8 10.2l4.6-1.6z",
        "M18 15l.7 1.8 1.8.7-1.8.7-.7 1.8-.7-1.8-1.8-.7 1.8-.7z",
    )
    val Person = icon(
        "person",
        "M12 11.5a3.7 3.7 0 1 0 0-7.4a3.7 3.7 0 0 0 0 7.4z",
        "M5 20c.7-3.6 3.5-5.9 7-5.9s6.3 2.3 7 5.9",
    )
    val Flag = icon("flag", "M5.5 20.5V4.5", "M5.5 4.8h11.3l-2.2 4 2.2 4H5.5")
    val Clock = icon("clock", "M12 3.5a8.5 8.5 0 1 1 0 17a8.5 8.5 0 0 1 0-17z", "M12 7.6V12l3 2")
    val Pause = icon("pause", "M9 6v12M15 6v12", stroke = 2f)
    val Hand = icon(
        "hand",
        "M8 12V6.5a1.5 1.5 0 0 1 3 0V11",
        "M11 10.5v-6a1.5 1.5 0 0 1 3 0v6",
        "M14 10.5V6a1.5 1.5 0 0 1 3 0v7.5c0 4-2.6 7-6.3 7-2.4 0-4-1.1-5.3-3.2l-2-3.4a1.5 1.5 0 0 1 2.6-1.5L8 14.5",
    )
}
