package ai.enthral.sentinel.capture

/**
 * Tiny on-device triage hint sent as `triage_score` (0..1). The server's rules are authoritative; a score >= 0.5
 * asks it to run the (expensive, budgeted) agent, so this stays conservative: one weak cue never crosses 0.5,
 * it takes at least two different kinds of cue (e.g. a payment demand plus a threat). Runs on redacted text.
 */
object Triage {
    private val cues: List<Pair<Regex, Double>> = listOf(
        // payment / credential demand
        Regex("""(?i)\b(pay|payment|transfer|send money|deposit|fee|penalty|fine|refund|cashback|upi pin|collect request)\b|भुगतान|जुर्माना|पैसे भेज""") to 0.3,
        // threat / urgency
        Regex("""(?i)\b(arrest|police|cbi|customs|court|warrant|blocked|suspended|disconnect\w*|seized|illegal|immediately|urgent|within \d+ (hours|hrs|minutes))\b|गिरफ्तार|तुरंत|बंद हो जाएगा""") to 0.3,
        // secrecy / remote access / OTP sharing ask
        Regex("""(?i)\b(do not tell|don't tell|keep this secret|anydesk|teamviewer|quick ?support|share (the )?(otp|code))\b|किसी को मत""") to 0.3,
        // link or UPI id
        Regex("""(?i)https?://|www\.|\b[a-z0-9.\-_]{2,}@(ybl|okaxis|okhdfcbank|oksbi|okicici|paytm|upi|ibl|axl)\b""") to 0.15,
        // prize / KYC / parcel lures
        Regex("""(?i)\b(kyc|lottery|prize|won|parcel|courier|package|electricity|lucky draw)\b|इनाम|पार्सल""") to 0.15,
    )

    fun score(redactedText: String): Double {
        val s = cues.sumOf { (rx, w) -> if (rx.containsMatchIn(redactedText)) w else 0.0 }
        return s.coerceIn(0.0, 1.0).let { Math.round(it * 100) / 100.0 }
    }
}
