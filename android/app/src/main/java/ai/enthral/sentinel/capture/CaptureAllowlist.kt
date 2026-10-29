package ai.enthral.sentinel.capture

import ai.enthral.sentinel.domain.Source

/**
 * The ONLY apps whose notifications Sentinel reads. Everything else is ignored before any field is touched.
 * Allowlist, never a blocklist: a new app is not watched until it is added here (and to docs/capture.md).
 *
 * - SMS apps -> "sms": the title is the SMS sender (phone number or operator header such as `VM-HDFCBK`).
 * - WhatsApp (and Business) -> "whatsapp".
 * - Gmail, Outlook -> "email".
 * - Telegram, UPI, wallet and bank apps -> "notification".
 *
 * Debug builds also accept `com.android.shell` (as "sms") so `adb shell cmd notification post ...` can inject test
 * notifications. That entry lives in src/debug (`DebugCaptureExtras`); the src/release copy is empty, so the string
 * is not even present in a release APK.
 */
object CaptureAllowlist {
    val SMS: Set<String> = setOf(
        "com.google.android.apps.messaging", // Google Messages
        "com.android.mms",                   // AOSP / many OEM SMS apps
        "com.samsung.android.messaging",     // Samsung Messages
        "com.motorola.messaging",            // Motorola Messages
        "com.oneplus.mms",                   // OnePlus Messages
        "com.android.messaging",             // AOSP Messaging
    )

    val WHATSAPP: Set<String> = setOf("com.whatsapp", "com.whatsapp.w4b")

    val EMAIL: Set<String> = setOf("com.google.android.gm", "com.microsoft.office.outlook")

    val MESSENGERS: Set<String> = setOf("org.telegram.messenger")

    val PAYMENTS_AND_BANKS: Set<String> = setOf(
        "com.google.android.apps.nbu.paisa.user", // Google Pay
        "com.phonepe.app",                        // PhonePe
        "net.one97.paytm",                        // Paytm
        "in.org.npci.upiapp",                     // BHIM
        "com.dreamplug.androidapp",               // CRED
        "in.amazon.mShop.android.shopping",       // Amazon (Amazon Pay)
        "com.mobikwik_new",                       // MobiKwik
        "com.freecharge.android",                 // Freecharge
        "com.csam.icici.bank.imobile",            // ICICI iMobile
        "com.snapwork.hdfc",                      // HDFC Bank
        "com.sbi.SBIFreedomPlus",                 // SBI YONO
        "com.sbi.lotusintouch",                   // YONO Lite SBI
        "com.axis.mobile",                        // Axis Mobile
        "com.msf.kbank.mobile",                   // Kotak
        "com.kotak811mobilebankingapp.instantsavingsupiscanandpayrecharge", // Kotak 811
        "com.infrasofttech.indianBank",           // Indian Bank
        "com.bankofbaroda.mconnect",              // Bank of Baroda
        "com.lcode.smartz",                       // Canara ai1
        "com.fss.unbipay",                        // Union Bank
        "com.pnb.pnbone",                         // PNB ONE
        "com.idbibank.abhay_card",                // IDBI
        "com.yesbank",                            // YES Bank
        "com.idfcfirstbank.optimus",              // IDFC FIRST
        "com.fedmobile",                          // Federal Bank FedMobile
    )

    private val base: Map<String, Source> = buildMap {
        SMS.forEach { put(it, Source.Sms) }
        WHATSAPP.forEach { put(it, Source.Whatsapp) }
        EMAIL.forEach { put(it, Source.Email) }
        MESSENGERS.forEach { put(it, Source.Notification) }
        PAYMENTS_AND_BANKS.forEach { put(it, Source.Notification) }
    }

    /** Package -> source for this build (debug adds the adb shell, see the class doc). */
    val active: Map<String, Source> by lazy { base + DebugCaptureExtras.packages }

    /** Package -> source without any build extras (tests, docs). */
    val release: Map<String, Source> get() = base

    /** Packages shown to the user in Settings (never includes the debug shell). */
    val userVisible: List<String> get() = base.keys.sorted()

    /**
     * Payment and bank apps: the title is a generic heading ("Money received"), not a sender, so it goes into the text
     * and `sender` stays empty. Every other app's title is a person, chat or SMS sender.
     */
    fun titleIsSender(packageName: String): Boolean = packageName !in PAYMENTS_AND_BANKS
}
