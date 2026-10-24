package ai.enthral.sentinel.data.api

import ai.enthral.sentinel.domain.Alert
import ai.enthral.sentinel.domain.WhyItem
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeParseException

/** Parses an ISO-8601 timestamp; naive values (no offset) are UTC, as the backend does. Falls back to epoch. */
internal fun parseInstant(value: String): Instant = try {
    Instant.parse(value)
} catch (_: DateTimeParseException) {
    try {
        OffsetDateTime.parse(value).toInstant()
    } catch (_: DateTimeParseException) {
        try {
            java.time.LocalDateTime.parse(value).toInstant(ZoneOffset.UTC)
        } catch (_: DateTimeParseException) {
            Instant.EPOCH
        }
    }
}

fun AlertOut.toDomain(): Alert = Alert(
    id = id,
    eventId = eventId,
    tier = tier,
    headline = headline,
    why = why.map { WhyItem(it.eventId, parseInstant(it.ts), it.text) }.sortedBy { it.timestamp },
    nextStep = nextStep,
    signals = signals,
    createdAt = parseInstant(createdAt),
)
