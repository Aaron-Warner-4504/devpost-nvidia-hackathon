package ai.enthral.sentinel.data.demo

import ai.enthral.sentinel.data.api.CallMeta
import ai.enthral.sentinel.data.api.EventIn
import ai.enthral.sentinel.domain.CallDirection
import ai.enthral.sentinel.domain.Source
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneOffset

/**
 * The 3-day courier SMS -> unknown call -> WhatsApp UPI demand story. Same payloads and stable event ids as
 * backend/sim/inject.py, so a re-run reports duplicates instead of creating new events. Timestamps are
 * 09:00 UTC on today-3, today-2 and today-1.
 */
object DemoTimeline {
    const val SCAMMER = "+919876543210"

    fun events(now: Instant): List<EventIn> {
        val day1 = now.atZone(ZoneOffset.UTC).toLocalDate().minusDays(3)
            .atTime(LocalTime.of(9, 0)).toInstant(ZoneOffset.UTC)
        return listOf(
            EventIn(
                deviceEventId = "demo-d1-courier-sms",
                source = Source.Sms,
                ts = day1.toString(),
                sender = "+917700112233",
                text = "Your BlueDart parcel is held at the depot due to an incomplete address. Call 9876543210 " +
                    "or visit http://bluedart-redelivery.in to reschedule.",
                triageScore = 0.3,
                lang = "en",
            ),
            EventIn(
                deviceEventId = "demo-d2-call",
                source = Source.Call,
                ts = day1.plus(Duration.ofDays(1)).toString(),
                call = CallMeta("+91 98765 43210", 240, false, CallDirection.In),
                triageScore = 0.4,
            ),
            EventIn(
                deviceEventId = "demo-d3-whatsapp",
                source = Source.Whatsapp,
                ts = day1.plus(Duration.ofDays(2)).toString(),
                sender = SCAMMER,
                text = "This is a customs officer. Your parcel is seized with illegal items. Pay Rs 25,000 penalty " +
                    "immediately to customs.clear@ybl or face arrest. Do not tell anyone.",
                triageScore = 0.9,
                lang = "en",
            ),
        )
    }

    val keys = listOf(DemoKey.CourierSms, DemoKey.UnknownCall, DemoKey.WhatsappUpi)
}
