package ai.enthral.sentinel.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewFontScale
import androidx.compose.ui.tooling.preview.PreviewLightDark
import ai.enthral.sentinel.domain.Alert
import ai.enthral.sentinel.domain.ConsentSource
import ai.enthral.sentinel.domain.ProtectionStatus
import ai.enthral.sentinel.domain.SourceStatus
import ai.enthral.sentinel.domain.Tier
import ai.enthral.sentinel.domain.WhyItem
import ai.enthral.sentinel.ui.theme.SentinelTheme
import ai.enthral.sentinel.ui.theme.Spacing
import java.time.Instant

private val now: Instant = Instant.parse("2026-10-24T12:00:00Z")

private val sampleAlert = Alert(
    id = "alr_1",
    eventId = "evt_3",
    tier = Tier.Critical,
    headline = "Likely scam: payment demand linked to earlier contacts",
    why = listOf(
        WhyItem("evt_1", now.minusSeconds(3 * 86_400), "Courier SMS with a link to a new website"),
        WhyItem("evt_2", now.minusSeconds(86_400), "Unknown caller from the same number, 4 minutes"),
    ),
    nextStep = "Do not pay or share an OTP. Call your bank on the number printed on your card.",
    signals = listOf("urgency", "payment_request", "secrecy_demand", "some_new_signal"),
    createdAt = now,
)

@Composable
private fun Frame(content: @Composable () -> Unit) {
    SentinelTheme {
        Surface {
            Column(Modifier.padding(Spacing.x2), verticalArrangement = Arrangement.spacedBy(Spacing.x1)) { content() }
        }
    }
}

/** Light + dark. */
@PreviewLightDark
@Composable
private fun TierBadgesPreview() = Frame { Tier.entries.forEach { TierBadge(it) } }

/** Font scales 0.85 .. 2.0 (the Home skeleton and cards must not clip). */
@PreviewFontScale
@Composable
private fun AlertCardPreview() = Frame { AlertCard(sampleAlert, onClick = {}) }

@PreviewLightDark
@Composable
private fun StatusHeroPreview() = Frame {
    ProtectionStatus.entries.forEach { StatusHero(it, "Notifications, Call info", onAction = {}) }
}

@PreviewLightDark
@Composable
private fun WhyTimelinePreview() = Frame { WhyTimeline(sampleAlert, now) }

@PreviewLightDark
@Composable
private fun SourceRowsPreview() = Frame {
    SourceStatus.entries.forEach { SourceRow(ConsentSource.Notifications, it, {}, {}, {}) }
}

/** Hindi: Devanagari line heights and wrapping. */
@Preview(locale = "hi", fontScale = 1.3f, showBackground = true)
@Composable
private fun HindiPreview() = Frame {
    AlertCard(sampleAlert, onClick = {})
    FeedbackBar(selected = null, onSelect = {})
    SignalChips(sampleAlert.signals)
}

@Preview(showBackground = true)
@Composable
private fun CodeEntryPreview() = Frame { CodeEntry(code = "K7M", onCodeChange = {}) }
