package ai.enthral.sentinel.capture

import ai.enthral.sentinel.domain.Source
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationFilterTest {
    private val filter = NotificationFilter(CaptureAllowlist.release + ("com.android.shell" to Source.Sms), "ai.enthral.sentinel")
    private fun n(pkg: String = "com.google.android.apps.messaging", text: String? = "hello", block: RawNotification.() -> RawNotification = { this }) =
        RawNotification(packageName = pkg, key = "0|$pkg|1|tag|1", postTime = 1_000L, title = "VM-HDFCBK", text = text).block()

    @Test fun `allowlisted apps map to their source`() {
        assertEquals(FilterDecision.Accept(Source.Sms), filter.decide(n()))
        assertEquals(FilterDecision.Accept(Source.Whatsapp), filter.decide(n("com.whatsapp")))
        assertEquals(FilterDecision.Accept(Source.Whatsapp), filter.decide(n("com.whatsapp.w4b")))
        assertEquals(FilterDecision.Accept(Source.Email), filter.decide(n("com.google.android.gm")))
        assertEquals(FilterDecision.Accept(Source.Notification), filter.decide(n("org.telegram.messenger")))
        assertEquals(FilterDecision.Accept(Source.Notification), filter.decide(n("com.phonepe.app")))
        assertEquals(FilterDecision.Accept(Source.Sms), filter.decide(n("com.motorola.messaging")))
    }

    @Test fun `everything else is ignored`() {
        listOf("com.instagram.android", "com.android.chrome", "org.thunderdog.challegram", "com.google.android.youtube").forEach {
            assertEquals(FilterDecision.Ignore(IgnoreReason.NotAllowlisted), filter.decide(n(it)))
        }
        assertEquals(FilterDecision.Ignore(IgnoreReason.OwnApp), NotificationFilter(mapOf("ai.enthral.sentinel" to Source.Sms), "ai.enthral.sentinel").decide(n("ai.enthral.sentinel")))
    }

    @Test fun `ongoing, foreground, summaries, system categories, media and progress are ignored`() {
        assertEquals(IgnoreReason.Ongoing, (filter.decide(n { copy(ongoing = true) }) as FilterDecision.Ignore).reason)
        assertEquals(IgnoreReason.ForegroundService, (filter.decide(n { copy(foregroundService = true) }) as FilterDecision.Ignore).reason)
        assertEquals(IgnoreReason.GroupSummary, (filter.decide(n { copy(groupSummary = true) }) as FilterDecision.Ignore).reason)
        listOf("call", "transport", "progress", "service", "status", "alarm").forEach { cat ->
            assertEquals(IgnoreReason.Category, (filter.decide(n { copy(category = cat) }) as FilterDecision.Ignore).reason)
        }
        assertEquals(FilterDecision.Accept(Source.Sms), filter.decide(n { copy(category = "msg") }))
        assertEquals(IgnoreReason.MediaOrProgress, (filter.decide(n { copy(isMedia = true) }) as FilterDecision.Ignore).reason)
        assertEquals(IgnoreReason.MediaOrProgress, (filter.decide(n { copy(hasProgress = true) }) as FilterDecision.Ignore).reason)
    }

    @Test fun `release allowlist never contains the adb shell and the release source set is empty`() {
        assertFalse(CaptureAllowlist.release.containsKey("com.android.shell"))
        assertFalse("com.android.shell" in CaptureAllowlist.userVisible)
        val release = listOf("src/release/java/ai/enthral/sentinel/capture/DebugCaptureExtras.kt", "app/src/release/java/ai/enthral/sentinel/capture/DebugCaptureExtras.kt")
            .map(::File).first { it.exists() }.readText()
        assertFalse(release.contains("com.android.shell"))
    }

    @Test fun `every watched package is declared in the manifest queries`() {
        val manifest = listOf("src/main/AndroidManifest.xml", "app/src/main/AndroidManifest.xml").map(::File).first { it.exists() }.readText()
        val declared = Regex("""<package android:name="([^"]+)"""").findAll(manifest).map { it.groupValues[1] }.toSet()
        assertEquals(CaptureAllowlist.userVisible.toSet(), declared)
    }

    @Test fun `extract prefers big text, then lines, then text`() {
        val big = NotificationExtractor.extract(n(text = "short") { copy(bigText = "the whole body", textLines = listOf("a")) }, Source.Sms, null)!!
        assertEquals("the whole body", big.text)
        val lines = NotificationExtractor.extract(n(text = "2 new") { copy(textLines = listOf("one", " ", "two")) }, Source.Sms, null)!!
        assertEquals("one\ntwo", lines.text)
        assertEquals("short", NotificationExtractor.extract(n(text = " short "), Source.Sms, null)!!.text)
        assertNull(NotificationExtractor.extract(n(text = "  "), Source.Sms, null))
        assertNull(NotificationExtractor.extract(n(text = null), Source.Sms, null))
    }

    @Test fun `payment app titles become part of the text, not the sender`() {
        val e = NotificationExtractor.extract(n("com.phonepe.app", "Rs 500 requested by x@ybl") { copy(title = "Collect request") }, Source.Notification, null)!!
        assertEquals("Collect request\nRs 500 requested by x@ybl", e.text)
        assertFalse(CaptureAllowlist.titleIsSender("com.phonepe.app"))
        assertTrue(CaptureAllowlist.titleIsSender("com.whatsapp"))
    }

    @Test fun `messaging style yields only messages newer than the cursor, oldest first`() {
        val msgs = listOf(
            RawMessage("first", 100, "Ravi", null),
            RawMessage("second", 200, "Ravi", "tel:+919876543210"),
            RawMessage("third", 300, "Ravi", null),
        )
        val raw = n("com.whatsapp", "3 messages") { copy(title = "Ravi", conversationTitle = null, messages = msgs) }
        val all = NotificationExtractor.extract(raw, Source.Whatsapp, null)!!
        assertEquals("first\nsecond\nthird", all.text)
        assertEquals(300L, all.cursorTs)
        assertEquals(listOf("tel:+919876543210"), all.personUris)
        val fresh = NotificationExtractor.extract(raw, Source.Whatsapp, 200)!!
        assertEquals("third", fresh.text)
        assertNull(NotificationExtractor.extract(raw, Source.Whatsapp, 300))
        assertEquals(all.conversationKey, fresh.conversationKey)
    }

    @Test fun `group conversations use the group name and never member uris`() {
        val raw = n("com.whatsapp") {
            copy(title = "Family: Ravi", conversationTitle = "Family", isGroupConversation = true,
                messages = listOf(RawMessage("pay now", 5, "Ravi", "content://com.android.contacts/contacts/lookup/abc/1")),
                peopleUris = listOf("tel:+919876543210"))
        }
        val e = NotificationExtractor.extract(raw, Source.Whatsapp, null)!!
        assertEquals("Family", e.title)
        assertTrue(e.isGroup)
        assertTrue(e.personUris.isEmpty())
    }
}
