package ai.enthral.sentinel.capture

import ai.enthral.sentinel.data.store.TokenCipher
import java.io.File
import java.net.URI
import java.net.URISyntaxException
import java.security.SecureRandom

/**
 * Which Person uris may prove "saved contact". Only uris naming ONE contact are accepted:
 *  - `content://com.android.contacts/contacts/lookup/<key>` and `.../contacts/lookup/<key>/<id>`
 *  - `content://com.android.contacts/contacts/<id>`
 *  - `content://com.android.contacts/phone_lookup/<number>` and `tel:<number>` (checked as an exact phone number)
 * Collections (`contacts`, `contacts/filter/...`, `data/...`), other authorities, query strings, fragments, `.`/`..`
 * or empty segments and anything else are rejected (never proof). The Android checker additionally requires the
 * accepted uri to resolve to exactly one row.
 */
object ContactUris {
    sealed interface Kind {
        data class Contact(val uri: String) : Kind
        data class Number(val number: String) : Kind
    }

    private const val AUTHORITY = "com.android.contacts"
    private val LOOKUP_KEY = Regex("""^[A-Za-z0-9._%\-]{1,512}$""")
    private val ID = Regex("""^[0-9]{1,18}$""")
    private val NUMBER = Regex("""^\+?[0-9 ()\-.]{6,24}$""")

    fun classify(raw: String): Kind? {
        if (raw.startsWith("tel:")) {
            val n = java.net.URLDecoder.decode(raw.removePrefix("tel:"), "UTF-8").trim()
            return if (NUMBER.matches(n)) Kind.Number(n) else null
        }
        val uri = try {
            URI(raw)
        } catch (_: URISyntaxException) {
            return null
        }
        if (uri.scheme != "content" || uri.rawAuthority != AUTHORITY) return null
        if (uri.rawQuery != null || uri.rawFragment != null || uri.rawUserInfo != null) return null
        val path = uri.rawPath ?: return null
        if (!path.startsWith("/")) return null
        val segs = path.substring(1).split('/')
        if (segs.any { it.isEmpty() || it == "." || it == ".." || it.contains("%2F", true) || it.contains("%2E%2E", true) }) return null
        return when {
            segs.size == 2 && segs[0] == "contacts" && ID.matches(segs[1]) -> Kind.Contact(raw)
            segs.size == 3 && segs[0] == "contacts" && segs[1] == "lookup" && LOOKUP_KEY.matches(segs[2]) -> Kind.Contact(raw)
            segs.size == 4 && segs[0] == "contacts" && segs[1] == "lookup" && LOOKUP_KEY.matches(segs[2]) &&
                ID.matches(segs[3]) -> Kind.Contact(raw)
            segs.size == 2 && segs[0] == "phone_lookup" -> {
                val n = java.net.URLDecoder.decode(segs[1], "UTF-8").trim()
                if (NUMBER.matches(n)) Kind.Number(n) else null
            }
            else -> null
        }
    }
}

/**
 * E.164 normalisation used to confirm a PhoneLookup hit EXACTLY (PhoneLookup itself does loose, suffix-based
 * matching, so a lookalike number could otherwise hit a saved contact). Indian rules are built in; other regions
 * use [fallback] (PhoneNumberUtils on the phone). Returns null when the number cannot be normalised confidently.
 */
object PhoneNormalizer {
    fun toE164(raw: String, region: String, fallback: (String, String) -> String? = { _, _ -> null }): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        val plus = trimmed.startsWith("+")
        val digits = trimmed.filter { it in '0'..'9' }
        if (digits.length < 6 || trimmed.any { !(it.isDigit() || it in "+ ()-./") }) return null
        if (plus) return "+$digits".takeIf { digits.length in 8..15 }
        if (digits.startsWith("00")) return "+${digits.drop(2)}".takeIf { digits.length - 2 in 8..15 }
        if (region.equals("IN", ignoreCase = true)) {
            return when {
                digits.length == 10 && digits[0] in '6'..'9' -> "+91$digits"
                digits.length == 11 && digits[0] == '0' -> "+91${digits.drop(1)}"
                digits.length == 12 && digits.startsWith("91") -> "+$digits"
                else -> null
            }
        }
        return fallback(trimmed, region.uppercase())
    }

    /** True only when both numbers normalise and are identical. */
    fun sameNumber(a: String, b: String, region: String, fallback: (String, String) -> String? = { _, _ -> null }): Boolean {
        val x = toE164(a, region, fallback) ?: return false
        val y = toE164(b, region, fallback) ?: return false
        return x == y
    }
}

/**
 * Per-install HMAC key for contact pseudonyms, stored encrypted with [cipher]. [reset] (delete-my-data) forgets the
 * key in memory AND on disk, so pseudonyms after a wipe cannot be linked to the ones before it.
 */
class PseudonymKeyStore(private val file: File, private val cipher: TokenCipher, private val random: SecureRandom = SecureRandom()) {
    private var cached: ByteArray? = null

    @Synchronized
    fun key(): ByteArray {
        cached?.let { return it }
        val loaded = if (file.exists()) cipher.decrypt(file.readBytes())?.takeIf { it.size == 32 } else null
        val key = loaded ?: ByteArray(32).also {
            random.nextBytes(it)
            file.parentFile?.mkdirs()
            file.writeBytes(cipher.encrypt(it))
        }
        cached = key
        return key
    }

    @Synchronized
    fun reset() {
        cached?.fill(0)
        cached = null
        file.delete()
    }
}
