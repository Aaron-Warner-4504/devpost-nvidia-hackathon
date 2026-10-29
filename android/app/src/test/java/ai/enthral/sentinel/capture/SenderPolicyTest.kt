package ai.enthral.sentinel.capture

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SenderPolicyTest {
    @get:Rule val tmp = TemporaryFolder()

    private val key = ByteArray(32) { it.toByte() }
    private val pseudo = Pseudonymizer(key)
    private val wa = "com.whatsapp"
    private val sms = "com.google.android.apps.messaging"
    private val lookupUri = "content://com.android.contacts/contacts/lookup/0r1-ABC/17"

    private fun policy(c: ContactChecker) = SenderPolicy(pseudo, c)

    @Test fun `pseudonym is stable, opaque and c_ plus 8 hex`() {
        val p = pseudo.pseudonym("Mummy")
        assertTrue(Regex("^c_[0-9a-f]{8}$").matches(p))
        assertEquals(p, pseudo.pseudonym(" mummy "))
        assertNotEquals(p, pseudo.pseudonym("Papa"))
        assertNotEquals(p, Pseudonymizer(ByteArray(32) { 9 }).pseudonym("Mummy"))
    }

    @Test fun `phone numbers and operator headers are sent as they are`() {
        val c = FakeContacts()
        assertEquals(SenderInfo("+91 98765 43210", false), policy(c).resolve(sms, "+91 98765 43210", emptyList(), false))
        assertEquals(SenderInfo("VM-HDFCBK", false), policy(c).resolve(sms, "VM-HDFCBK", emptyList(), false))
        assertEquals(SenderInfo("JD-HDFCBK-S", false), policy(c).resolve(sms, "JD-HDFCBK-S", emptyList(), false))
    }

    @Test fun `a saved number is is_contact true only after an exact match`() {
        val c = FakeContacts(numbers = setOf("+919876543210"))
        assertEquals(SenderInfo("09876543210", true), policy(c).resolve(sms, "09876543210", emptyList(), false))
        // same last digits, different prefix: not the contact
        assertEquals(SenderInfo("+447876543210", false), policy(c).resolve(sms, "+447876543210", emptyList(), false))
    }

    @Test fun `contact names never leave the phone`() {
        val c = FakeContacts(names = setOf("Mummy"), uris = mapOf(lookupUri to 17L))
        val proven = policy(c).resolve(wa, "Mummy", listOf(lookupUri), false)
        assertEquals(SenderInfo(pseudo.pseudonym("Mummy"), true), proven)
        // name matches a contact but nothing proves it: hidden, and NOT is_contact (display names are spoofable)
        val nameOnly = policy(c).resolve(wa, "Mummy", emptyList(), false)
        assertEquals(SenderInfo(pseudo.pseudonym("Mummy"), false), nameOnly)
    }

    @Test fun `a display name alone never sets is_contact`() {
        val c = FakeContacts(names = setOf("HDFC Bank"))
        assertFalse(policy(c).resolve(wa, "HDFC Bank", emptyList(), false).isContact == true)
        assertFalse(policy(c).resolve(wa, "HDFC Bank", listOf("mailto:a@b.c", "name:HDFC Bank"), false).isContact == true)
    }

    @Test fun `unknown names are sent raw and capped at 200`() {
        val c = FakeContacts()
        assertEquals(SenderInfo("Customs Officer", false), policy(c).resolve("org.telegram.messenger", "Customs Officer", emptyList(), false))
        assertEquals(200, policy(c).resolve(wa, "x".repeat(500), emptyList(), false).sender!!.length)
    }

    @Test fun `without READ_CONTACTS names fail closed and is_contact is unknown`() {
        val c = FakeContacts(granted = false)
        assertEquals(SenderInfo(pseudo.pseudonym("Ravi"), null), policy(c).resolve(wa, "Ravi", listOf(lookupUri), false))
        assertEquals(SenderInfo("+919876543210", null), policy(c).resolve(sms, "+919876543210", emptyList(), false))
        // a bare 6-letter title could be a contact saved in capitals: hidden unless the check ran
        assertEquals(pseudo.pseudonym("PAPAJI"), policy(c).resolve(sms, "PAPAJI", emptyList(), false).sender)
        assertEquals("HDFCBK", policy(FakeContacts()).resolve(sms, "HDFCBK", emptyList(), false).sender)
        assertEquals(pseudo.pseudonym("PAPAJI"), policy(FakeContacts(names = setOf("Papaji"))).resolve(sms, "PAPAJI", emptyList(), false).sender)
    }

    @Test fun `groups are never is_contact`() {
        val c = FakeContacts(uris = mapOf(lookupUri to 17L), names = setOf("Family"))
        val r = policy(c).resolve(wa, "Family", listOf(lookupUri), true)
        assertEquals(false, r.isContact)
        assertEquals(pseudo.pseudonym("Family"), r.sender)
    }

    @Test fun `payment apps send no sender`() {
        assertEquals(SenderInfo(null, null), policy(FakeContacts()).resolve("com.phonepe.app", "Money received", emptyList(), false))
    }

    @Test fun `only single-contact uris are ever passed to the contact checker`() {
        val c = FakeContacts(uris = mapOf("content://com.android.contacts/contacts" to 1L))
        val bad = listOf(
            "content://com.android.contacts/contacts",
            "content://com.android.contacts/contacts/filter/ravi",
            "content://com.android.contacts/data/5",
            "content://com.android.contacts/raw_contacts/5",
            "content://com.android.contacts/contacts/lookup/../data",
            "content://evil.provider/contacts/5",
            "content://com.android.contacts/contacts/5",
            "content://com.android.contacts/contacts/lookup/0r1-ABC",
            "content://com.android.contacts/contacts/lookup/0nRavi/5",
        )
        assertEquals(false, policy(c).resolve(wa, "Ravi", bad, false).isContact)
        assertTrue(c.uriQueries.isEmpty())
    }

    @Test fun `contact uri accept and reject table`() {
        assertEquals(ContactUris.Kind.Contact("content://com.android.contacts/contacts/lookup/0r1-2F3A4B/42", 42),
            ContactUris.classify("content://com.android.contacts/contacts/lookup/0r1-2F3A4B/42"))
        assertTrue(ContactUris.classify("content://com.android.contacts/contacts/lookup/3789r4-2F4D.1234i5/17") is ContactUris.Kind.Contact)
        assertEquals(ContactUris.Kind.Number("+919876543210"), ContactUris.classify("tel:+919876543210"))
        assertEquals(ContactUris.Kind.Number("+91 98765 43210"), ContactUris.classify("tel:%2B91%2098765%2043210"))
        assertEquals(ContactUris.Kind.Number("9876543210"), ContactUris.classify("content://com.android.contacts/phone_lookup/9876543210"))
        val reject = listOf(
            "content://com.android.contacts/contacts",
            "content://com.android.contacts/contacts/",
            "content://com.android.contacts/contacts/42",                 // bare id: not corroborated
            "content://com.android.contacts/contacts/lookup/0r1-2F3A4B",  // key without id
            "content://com.android.contacts/contacts/lookup/0nRavi/42",   // display-name derived key
            "content://com.android.contacts/contacts/lookup/3789r4.0nRavi/42",
            "content://com.android.contacts/contacts/lookup/abc/0",
            "content://com.android.contacts/contacts/filter/ravi",
            "content://com.android.contacts/contacts/lookup",
            "content://com.android.contacts/contacts/lookup/abc/notanid",
            "content://com.android.contacts/contacts/lookup/abc/1/data",
            "content://com.android.contacts/contacts/42/photo",
            "content://com.android.contacts/contacts/lookup/abc/42?limit=1",
            "content://com.android.contacts/contacts/lookup/abc/42#x",
            "content://com.android.contacts/data/42",
            "content://com.android.contacts/raw_contacts/42",
            "content://com.android.contacts/contacts/lookup/../42",
            "content://com.android.contacts/contacts/lookup/a%2F..%2Fb/42",
            "content://com.android.contacts//contacts/lookup/abc/42",
            "content://com.android.contacts.evil/contacts/lookup/abc/42",
            "content://user@com.android.contacts/contacts/lookup/abc/42",
            "content://media/external/contacts/42",
            "file:///sdcard/contacts/42",
            "mailto:ravi@example.com",
            "name:Ravi",
            "tel:call-me-now",
            "tel:%ZZ12345678",            // malformed escape
            "tel:%2",                     // truncated escape
            "tel:%C3%28123456789",        // invalid UTF-8
            "tel:" + "9".repeat(5000),
            "content://com.android.contacts/phone_lookup/abc",
            "content://com.android.contacts/phone_lookup/%ZZ",
            "",
            "not a uri at all",
            "content://com.android.contacts/contacts/lookup/\u0000/42",
        )
        reject.forEach { assertNull(it, ContactUris.classify(it)) }
    }

    @Test fun `lookup uri is proof only when it resolves to the same id`() {
        val uri = "content://com.android.contacts/contacts/lookup/0r1-AB/17"
        val c = FakeContacts(uris = mapOf(uri to 99L)) // resolves to a different contact
        assertEquals(false, policy(c).resolve(wa, "Ravi", listOf(uri), false).isContact)
        val good = FakeContacts(uris = mapOf(uri to 17L))
        assertEquals(true, policy(good).resolve(wa, "Ravi", listOf(uri), false).isContact)
    }

    @Test fun `pseudonyms stay valid while the key is reset concurrently`() {
        val store = PseudonymKeyStore(File(tmp.root, "kc"), XorCipher())
        val p = Pseudonymizer(store::key)
        val errors = java.util.concurrent.ConcurrentLinkedQueue<Throwable>()
        val bad = java.util.concurrent.ConcurrentLinkedQueue<String>()
        val workers = (1..6).map { t ->
            Thread {
                repeat(400) { i ->
                    try {
                        val out = p.pseudonym("name$t-$i")
                        if (!Regex("^c_[0-9a-f]{8}$").matches(out)) bad += out
                    } catch (e: Throwable) { errors += e }
                }
            }
        }
        val resetter = Thread { repeat(300) { store.reset(); Thread.yield() } }
        (workers + resetter).forEach { it.start() }
        (workers + resetter).forEach { it.join() }
        assertTrue(errors.toString(), errors.isEmpty())
        assertTrue(bad.toString(), bad.isEmpty())
    }

    @Test fun `key returns a defensive copy`() {
        val store = PseudonymKeyStore(File(tmp.root, "kd"), XorCipher())
        val k = store.key()
        k.fill(0)
        assertFalse(store.key().all { it == 0.toByte() })
    }

    @Test fun `exact E164 matching rejects near misses`() {
        assertTrue(PhoneNormalizer.sameNumber("+91 98765 43210", "09876543210", "IN"))
        assertTrue(PhoneNormalizer.sameNumber("0091-9876543210", "919876543210", "IN"))
        assertTrue(PhoneNormalizer.sameNumber("98765-43210", "+919876543210", "IN"))
        assertFalse(PhoneNormalizer.sameNumber("+919876543210", "+449876543210", "IN")) // different country
        assertFalse(PhoneNormalizer.sameNumber("+919876543210", "+919876543211", "IN")) // last digit
        assertFalse(PhoneNormalizer.sameNumber("+919876543210", "+918876543210", "IN")) // same last 9 digits
        assertFalse(PhoneNormalizer.sameNumber("+919876543210", "6543210", "IN"))        // short / missing prefix
        assertFalse(PhoneNormalizer.sameNumber("+919876543210", "1234567890", "IN"))     // not a mobile shape
        assertNull(PhoneNormalizer.toE164("98765 43210 ext 5", "IN"))
        assertNull(PhoneNormalizer.toE164("9876543210", "US")) // no confident rule without the platform fallback
        assertEquals("+12025550123", PhoneNormalizer.toE164("2025550123", "US") { _, _ -> "+12025550123" })
    }

    @Test fun `pseudonym key reset makes pseudonyms unlinkable across a wipe`() {
        val store = PseudonymKeyStore(File(tmp.root, "k"), XorCipher())
        val p = Pseudonymizer(store::key)
        val before = p.pseudonym("Mummy")
        assertEquals(before, p.pseudonym("Mummy"))
        assertEquals(before, Pseudonymizer(PseudonymKeyStore(File(tmp.root, "k"), XorCipher())::key).pseudonym("Mummy"))
        store.reset()
        assertFalse(File(tmp.root, "k").exists())
        val after = p.pseudonym("Mummy")
        assertNotEquals(before, after)
        assertEquals(after, p.pseudonym("Mummy"))
    }

    @Test fun `unreadable pseudonym key file is replaced`() {
        File(tmp.root, "k").writeBytes(byteArrayOf(1, 2, 3))
        val key = PseudonymKeyStore(File(tmp.root, "k"), XorCipher()).key()
        assertEquals(32, key.size)
    }
}
