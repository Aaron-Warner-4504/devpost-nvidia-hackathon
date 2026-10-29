package ai.enthral.sentinel.capture

import java.util.regex.Matcher
import java.util.regex.Pattern

/**
 * On-device port of backend/app/pipeline/redact.py. Runs on every captured text BEFORE it is queued or sent.
 *
 * Masks OTPs, card / account / Aadhaar numbers and PAN with `[OTP] [CARD] [ACCT] [AADHAAR] [PAN]`. Phone numbers,
 * URLs, UPI ids and amounts are kept on purpose: they are scam evidence. English and Hindi (Devanagari digits are
 * recognised). Same rules, same order and the same fail-closed Aadhaar precedence as the backend.
 *
 * Parity with Python: `RedactorParityTest` runs this over the golden file produced by the real Python redactor
 * (android/scripts/gen-redact-golden.py). To match Python's Unicode `re` semantics on the JVM / Android:
 * - `\d` is spelled `\p{Nd}` (Python's `\d` matches every Unicode decimal digit, not only ASCII);
 * - `\s` is the exact set of code points Python's `str.isspace()` accepts ([WS]);
 * - `\b` is spelled as explicit lookarounds over Python's word class (letters, numbers, underscore);
 * - IGNORECASE ranges `[A-Z]` also accept the four non-ASCII letters Python accepts (U+0130, U+0131, U+017F, U+212A);
 * - `search(text, pos, endpos)` is emulated with a region over `text[0:endpos]` and transparent bounds
 *   (Python lets look-behind see before `pos` but treats `endpos` as the end of the string);
 * - lengths and windows are counted in code points, like Python.
 */
object Redactor {
    /** Python `str.isspace()` code points (generated from Python, see the class doc). */
    private const val WS =
        "[\\t\\n\\x0B\\f\\r\\x1C-\\x1F \\x85\\xA0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000]"
    private const val D = "\\p{Nd}"
    private const val ND = "[^\\p{Nd}]"
    private const val WORD = "[\\p{L}\\p{N}_]"

    /** ASCII letters plus the extra letters Python's IGNORECASE `[A-Z]`/`[a-z]` match. */
    private const val LETTER_CI = "A-Za-z\\u0130\\u0131\\u017F\\u212A"

    private const val FLAGS = Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE or Pattern.UNIX_LINES

    private val PAN = Pattern.compile(
        "(?<![${LETTER_CI}0-9])[$LETTER_CI]{5}[0-9]{4}[$LETTER_CI](?![${LETTER_CI}0-9])",
        FLAGS,
    )
    private val ACCT_CTX = Pattern.compile(
        "((?:a/c|acct?|account|खाता)(?:$WS*(?:no\\.?|number|num|संख्या))?(?:$WS|[:.#-])*)" +
            "($D(?:[ -]?$D){5,17})(?!$D)",
        FLAGS,
    )
    private val AADHAAR_CTX = Pattern.compile(
        "((?<![$LETTER_CI])(?!(?-i:AADHAAR\\]))(?:aadhaar|aadhar|आधार|uid)$ND{0,20}?)($D(?:[ -]?$D){11})(?!$D)",
        FLAGS,
    )
    private val PHONE_KW = Pattern.compile(
        "(?<!$WORD)(?:call|phone|mob|mobile|whatsapp|contact)(?!$WORD)|संपर्क|कॉल|फोन|मोबाइल",
        FLAGS,
    )
    private val RUN = Pattern.compile("(?<!$D)$D(?:[ -]?$D){8,18}(?!$D)", FLAGS)
    private const val KW =
        "(?:otp|code|verification|ओटीपी|कोड|pin|one[ -]time[ -]password|passcode|पासवर्ड)"
    private const val NUM =
        "(?<!$D)(?<!$D[ -])($D{4,8}(?:[ -]$D{2,4})?|$D{3}[ -]$D{3,4})(?!$D)(?![ -]$D)"
    private val OTP_BEFORE = Pattern.compile("$KW$ND{0,30}?$NUM", FLAGS)
    private val OTP_AFTER = Pattern.compile("$NUM$ND{0,30}?$KW", FLAGS)
    private val CURRENCY_BEFORE = Pattern.compile(
        "(?:₹|रु\\.?|रुपये|(?<![$LETTER_CI])(?:rs\\.?|inr))$WS*$",
        FLAGS,
    )
    private val PHONE = Pattern.compile("(?:91|0091|0)?[6-9]$D{9}")
    private val PREFIX_91 = Pattern.compile("(?:91|0091)[ -]")
    private val AADHAAR_GROUPED = Pattern.compile("$D{4}([ -])$D{4}\\1$D{4}")
    private val CARD_GROUPED = Pattern.compile("$D{4}([ -])$D{4}\\1$D{4}\\1$D{1,7}")

    private data class Span(val start: Int, val end: Int, val token: String)

    fun redact(text: String): String {
        var t = text
        t = sub(PAN, t) { m, _ -> listOf(Span(m.start(), m.end(), "[PAN]")) }
        t = sub(ACCT_CTX, t) { m, _ -> listOf(Span(m.start(2), m.end(2), "[ACCT]")) }
        t = sub(AADHAAR_CTX, t) { m, _ -> listOf(Span(m.start(2), m.end(2), "[AADHAAR]")) }
        t = sub(RUN, t, ::run)
        t = sub(OTP_BEFORE, t, ::otp)
        t = sub(OTP_AFTER, t, ::otp)
        return t
    }

    /** Devanagari digits -> ASCII, keeping length so offsets map 1:1 onto the original text. */
    private fun normalise(text: String): String {
        if (text.none { it in '०'..'९' }) return text
        val chars = text.toCharArray()
        for (i in chars.indices) if (chars[i] in '०'..'९') chars[i] = '0' + (chars[i] - '०')
        return String(chars)
    }

    private inline fun sub(rx: Pattern, text: String, fn: (Matcher, String) -> List<Span>?): String {
        val norm = normalise(text)
        val spans = ArrayList<Span>()
        val m = rx.matcher(norm)
        while (m.find()) fn(m, norm)?.let { spans.addAll(it) }
        if (spans.isEmpty()) return text
        val sb = StringBuilder(text)
        spans.sortedWith(compareByDescending<Span> { it.start }.thenByDescending { it.end })
            .forEach { sb.replace(it.start, it.end, it.token) }
        return sb.toString()
    }

    /** Python `rx.search(norm, pos, endpos)` where pos/endpos are UTF-16 offsets. */
    private fun search(rx: Pattern, norm: String, from: Int, to: Int): Boolean =
        rx.matcher(norm.substring(0, to)).useTransparentBounds(true).useAnchoringBounds(true)
            .region(from, to).find()

    /** Offset [cps] code points before [index], clamped at 0 (Python `max(0, start - n)`). */
    private fun back(s: String, index: Int, cps: Int): Int {
        val available = s.codePointCount(0, index)
        return s.offsetByCodePoints(index, -minOf(cps, available))
    }

    private fun afterCurrency(norm: String, start: Int): Boolean =
        search(CURRENCY_BEFORE, norm, back(norm, start, 8), start)

    private fun isPhone12(raw: String, norm: String, start: Int): Boolean {
        if (start > 0 && norm[start - 1] == '+') return true
        if (PREFIX_91.matcher(raw).lookingAt()) return true
        if (AADHAAR_GROUPED.matcher(raw).matches()) return false
        return search(PHONE_KW, norm, back(norm, start, 20), start)
    }

    private fun digitsOnly(raw: String): String {
        val sb = StringBuilder()
        raw.codePoints().forEach { if (Character.getType(it) == Character.DECIMAL_DIGIT_NUMBER.toInt()) sb.appendCodePoint(it) }
        return sb.toString()
    }

    private fun run(m: Matcher, norm: String): List<Span>? {
        val raw = m.group()
        val d = digitsOnly(raw)
        val n = d.codePointCount(0, d.length)
        if (afterCurrency(norm, m.start())) return null
        if (PHONE.matcher(d).matches()) {
            if (n != 12 || isPhone12(raw, norm, m.start())) return null // phone number: evidence, keep
        }
        val token = when {
            n == 12 -> "[AADHAAR]"
            n in 13..19 -> {
                val grouped = CARD_GROUPED.matcher(raw).matches()
                if (n == 15 || n == 16 || grouped || n == 19) "[CARD]" else "[ACCT]"
            }
            else -> "[ACCT]"
        }
        return listOf(Span(m.start(), m.end(), token))
    }

    private fun otp(m: Matcher, norm: String): List<Span>? {
        if (afterCurrency(norm, m.start(1))) return null
        return listOf(Span(m.start(1), m.end(1), "[OTP]"))
    }
}
