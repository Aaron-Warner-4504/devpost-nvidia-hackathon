package ai.enthral.sentinel.capture

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Contact checks backed by ContactsContract. Every call returns null when READ_CONTACTS is not granted. */
interface ContactChecker {
    /** True when [uri] (a Person uri: contacts lookup uri or `tel:`) resolves to a saved contact. */
    fun isSavedContactUri(uri: String): Boolean?

    /** True when [number] belongs to a saved contact (PhoneLookup). */
    fun isSavedNumber(number: String): Boolean?

    /** True when a saved contact has exactly this display name. Never used to set is_contact (names are spoofable). */
    fun isContactName(name: String): Boolean?
}

/** Opaque, stable, per-install pseudonym: `c_<8 hex of HMAC-SHA256(key, name)>`. The key never leaves the phone. */
class Pseudonymizer(private val key: ByteArray) {
    fun pseudonym(name: String): String {
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }
        val digest = mac.doFinal(name.trim().lowercase().toByteArray(Charsets.UTF_8))
        return "c_" + digest.take(4).joinToString("") { "%02x".format(it) }
    }
}

data class SenderInfo(val sender: String?, val isContact: Boolean?)

/**
 * What goes into `sender` and `is_contact`:
 * - payment / bank apps: no sender (their title is a heading, it goes into the text);
 * - phone numbers and SMS headers (`VM-HDFCBK`) are evidence and are sent as they are;
 * - a name that is (or may be) a saved contact never leaves the phone: it becomes a pseudonym. Without READ_CONTACTS
 *   we cannot tell, so names fail closed to the pseudonym too;
 * - other names (e.g. an unknown Telegram display name) are sent capped at 200 chars.
 * `is_contact` is true ONLY when a Person uri or the sender's phone number resolves to a saved contact; a display
 * name alone never sets it. Group chats are never `is_contact`. Unknown (no permission) -> null.
 */
class SenderPolicy(private val pseudonyms: Pseudonymizer, private val contacts: ContactChecker) {

    fun resolve(packageName: String, title: String?, personUris: List<String>, isGroup: Boolean): SenderInfo {
        if (!CaptureAllowlist.titleIsSender(packageName)) return SenderInfo(null, null)
        val t = title?.trim()?.takeIf { it.isNotEmpty() } ?: return SenderInfo(null, proofFromUris(personUris, isGroup))
        if (looksLikePhone(t)) {
            val byNumber = contacts.isSavedNumber(t)
            val byUri = proofFromUris(personUris, isGroup)
            val isContact = when {
                isGroup -> false
                byNumber == true || byUri == true -> true
                byNumber == null && byUri == null -> null
                else -> false
            }
            return SenderInfo(t.take(MAX_SENDER), isContact)
        }
        val nameMatch = contacts.isContactName(t)
        // Operator-prefixed headers are never contact names. A bare 6-letter header ("HDFCBK") could be a contact
        // saved as e.g. "PAPAJI", so it is sent only when the contact check ran and found no such name.
        if (looksLikeSmsHeader(t) || (looksLikeBareHeader(t) && nameMatch == false)) {
            return SenderInfo(t.take(MAX_SENDER), false)
        }

        val proof = proofFromUris(personUris, isGroup)
        val isContact = when {
            isGroup -> false
            proof == true -> true
            proof == null && nameMatch == null -> null
            else -> false
        }
        val mustHide = proof == true || nameMatch == true || nameMatch == null
        return SenderInfo(if (mustHide) pseudonyms.pseudonym(t) else t.take(MAX_SENDER), isContact)
    }

    private fun proofFromUris(uris: List<String>, isGroup: Boolean): Boolean? {
        if (isGroup) return false
        var unknown = false
        for (uri in uris) {
            val r = if (uri.startsWith("tel:")) contacts.isSavedNumber(uri.removePrefix("tel:"))
            else if (uri.startsWith("content://com.android.contacts/")) contacts.isSavedContactUri(uri)
            else false
            if (r == true) return true
            if (r == null) unknown = true
        }
        return if (unknown) null else false
    }

    companion object {
        const val MAX_SENDER = 200
        private val PHONE = Regex("""^\+?[0-9][0-9 ()\-.]{5,19}$""")
        // Operator-prefixed DLT headers: VM-HDFCBK, JD-HDFCBK-S, AD-123456.
        private val SMS_HEADER = Regex("""^[A-Z]{2}-[A-Z0-9]{3,9}(?:-[A-Z])?$""")
        private val BARE_HEADER = Regex("""^[A-Z]{6}$""")

        fun looksLikePhone(s: String): Boolean = PHONE.matches(s) && s.count { it.isDigit() } in 6..15
        fun looksLikeSmsHeader(s: String): Boolean = SMS_HEADER.matches(s)
        fun looksLikeBareHeader(s: String): Boolean = BARE_HEADER.matches(s)
    }
}
