package ai.enthral.sentinel.capture

import ai.enthral.sentinel.data.store.TokenCipher
import java.io.File
import java.net.URI
import java.net.URISyntaxException
import java.security.SecureRandom

/**
 * Which Person uris may prove "saved contact". Only uris naming ONE contact are accepted:
 *  - `content://com.android.contacts/contacts/lookup/<key>/<id>` (key not display-name derived); the phone then
 *    requires it to resolve to exactly one contact whose _ID equals <id>;
 *  - `content://com.android.contacts/phone_lookup/<number>` and `tel:<number>` (checked as an exact E.164 number).
 * Bare `contacts/<id>`, lookup keys without an id, collections (`contacts`, `contacts/filter/...`, `data/...`), other
 * authorities, query strings, fragments, escapes in content paths, `.`/`..` or empty segments, malformed escapes and
 * anything else are rejected (never proof, never an exception).
 */
object ContactUris {
    sealed interface Kind {
        /** A lookup uri with its contact id; proof only if it resolves to exactly that one contact. */
        data class Contact(val uri: String, val id: Long) : Kind
        data class Number(val number: String) : Kind
    }

    private const val AUTHORITY = "com.android.contacts"
    private val LOOKUP_KEY = Regex("""^[A-Za-z0-9._\-]{1,512}$""")
    private val ID = Regex("""^[1-9][0-9]{0,17}$""")
    private val NUMBER = Regex("""^\+?[0-9 ()\-.]{6,24}$""")

    /**
     * Lookup-key parts are `<account hash><type><data>`; type `n` means the key was derived from the DISPLAY NAME
     * only (no stable row identity), which a spoofed name could reproduce: never proof.
     */
    private val NAME_KEY_PART = Regex("""^[0-9]*n""")

    fun classify(raw: String): Kind? = try {
        classifyStrict(raw)
    } catch (_: RuntimeException) {
        null // malformed escapes, odd encodings: never proof, never a crash
    }

    private fun classifyStrict(raw: String): Kind? {
        if (raw.length > 2048) return null
        if (raw.startsWith("tel:")) {
            val n = percentDecode(raw.removePrefix("tel:"))?.trim() ?: return null
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
        if (!path.startsWith("/") || path.contains('%')) return null
        val segs = path.substring(1).split('/')
        if (segs.any { it.isEmpty() || it == "." || it == ".." }) return null
        return when {
            // Only content://com.android.contacts/contacts/lookup/<key>/<id>. A bare contacts/<id> or a lookup key
            // without its id is not accepted: the id must be corroborated by the key (checked on the phone).
            segs.size == 4 && segs[0] == "contacts" && segs[1] == "lookup" && LOOKUP_KEY.matches(segs[2]) &&
                ID.matches(segs[3]) && segs[2].split('.').none { NAME_KEY_PART.containsMatchIn(it) } ->
                Kind.Contact(raw, segs[3].toLong())
            segs.size == 2 && segs[0] == "phone_lookup" -> {
                val n = percentDecode(segs[1])?.trim() ?: return null
                if (NUMBER.matches(n)) Kind.Number(n) else null
            }
            else -> null
        }
    }

    /** RFC 3986 percent-decoding ('+' stays '+'); null on a malformed escape or invalid UTF-8. */
    internal fun percentDecode(s: String): String? {
        if ('%' !in s) return s
        val out = java.io.ByteArrayOutputStream()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '%') {
                if (i + 2 >= s.length) return null
                val hi = Character.digit(s[i + 1], 16)
                val lo = Character.digit(s[i + 2], 16)
                if (hi < 0 || lo < 0) return null
                out.write(hi * 16 + lo)
                i += 3
            } else {
                out.write(c.toString().toByteArray(Charsets.UTF_8))
                i++
            }
        }
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
        return try {
            decoder.decode(java.nio.ByteBuffer.wrap(out.toByteArray())).toString()
        } catch (_: java.nio.charset.CharacterCodingException) {
            null
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

    /** Returns a COPY: callers may keep using it while [reset] runs on another thread. */
    @Synchronized
    fun key(): ByteArray {
        cached?.let { return it.copyOf() }
        val loaded = if (file.exists()) cipher.decrypt(file.readBytes())?.takeIf { it.size == 32 } else null
        val key = loaded ?: ByteArray(32).also {
            random.nextBytes(it)
            file.parentFile?.mkdirs()
            file.writeBytes(cipher.encrypt(it))
        }
        cached = key
        return key.copyOf()
    }

    /** Drops the reference (never zeroes an array another thread may be reading) and deletes the file. */
    @Synchronized
    fun reset() {
        cached = null
        file.delete()
    }
}
