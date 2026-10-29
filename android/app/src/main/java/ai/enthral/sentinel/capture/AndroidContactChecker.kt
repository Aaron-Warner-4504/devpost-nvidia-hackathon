package ai.enthral.sentinel.capture

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import android.telephony.PhoneNumberUtils
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat

/**
 * ContactsContract lookups, only answering yes/no. No contact data is copied, stored or sent.
 * Every method returns null when READ_CONTACTS is not granted (or the provider fails).
 */
class AndroidContactChecker(private val context: Context) : ContactChecker {
    private fun granted() = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) ==
        PackageManager.PERMISSION_GRANTED

    /** SIM country (falls back to network country, then IN). */
    private val region: String by lazy {
        val tm = context.getSystemService(TelephonyManager::class.java)
        (tm?.simCountryIso?.takeIf { it.length == 2 } ?: tm?.networkCountryIso?.takeIf { it.length == 2 } ?: "in").uppercase()
    }

    private val e164Fallback: (String, String) -> String? = { n, r -> PhoneNumberUtils.formatNumberToE164(n, r) }

    private inline fun query(block: () -> Boolean): Boolean? {
        if (!granted()) return null
        return try {
            block()
        } catch (_: SecurityException) {
            null
        } catch (_: IllegalArgumentException) {
            false
        } catch (_: UnsupportedOperationException) {
            false
        }
    }

    /** Proof only when the validated lookup uri resolves to exactly one contact row whose _ID is [expectedId]. */
    override fun isSavedContactUri(uri: String, expectedId: Long): Boolean? = query {
        context.contentResolver.query(Uri.parse(uri), arrayOf(ContactsContract.Contacts._ID), null, null, null)
            ?.use { it.count == 1 && it.moveToFirst() && it.getLong(0) == expectedId } ?: false
    }

    /**
     * PhoneLookup matches loosely (by trailing digits), so a hit is only a candidate: the matched row's number must
     * normalise to exactly the same E.164 number as [number].
     */
    override fun isSavedNumber(number: String): Boolean? = query {
        val target = PhoneNormalizer.toE164(number, region, e164Fallback) ?: return@query false
        val lookup = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
        val cols = arrayOf(ContactsContract.PhoneLookup.NUMBER, ContactsContract.PhoneLookup.NORMALIZED_NUMBER)
        context.contentResolver.query(lookup, cols, null, null, null)?.use { c ->
            var exact = false
            while (!exact && c.moveToNext()) {
                val candidates = listOfNotNull(c.getString(1), c.getString(0))
                exact = candidates.any { PhoneNormalizer.toE164(it, region, e164Fallback) == target }
            }
            exact
        } ?: false
    }

    override fun isContactName(name: String): Boolean? = query {
        context.contentResolver.query(
            ContactsContract.Contacts.CONTENT_URI,
            arrayOf(ContactsContract.Contacts._ID),
            "${ContactsContract.Contacts.DISPLAY_NAME_PRIMARY} = ? COLLATE NOCASE",
            arrayOf(name.trim()),
            null,
        )?.use { it.moveToFirst() } ?: false
    }
}
